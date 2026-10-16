"""Host-side stage contract for the original SHARP sliding pyramid.

This module only schedules and stitches the unchanged 384px patch encoder.
It does not resize position embeddings, quantize weights, or replace SHARP.
The caller supplies the three already normalized pyramid levels and a
batch-one encoder. Decoder/image-encoder execution remains a separate stage.

Reference: apple-aiml-research/ml-sharp, sharp/models/encoders/spn_encoder.py.
The upstream model and weights retain their own license terms.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Callable, Iterator, Sequence

import numpy as np


PATCH_SIZE = 384
TOKEN_GRID = 24
PATCH_COUNT = 35


@dataclass(frozen=True)
class Patch:
    index: int
    level: int
    row: int
    column: int
    pixels: np.ndarray


@dataclass(frozen=True)
class Features:
    """Unprojected SPN features, before the trained upsampling layers."""

    latent0: np.ndarray
    latent1: np.ndarray
    high: np.ndarray
    middle: np.ndarray
    low: np.ndarray


def patches(pyramid: Sequence[np.ndarray]) -> Iterator[Patch]:
    """Yield 25 + 9 + 1 overlapping windows without a batch-35 allocation.

    Levels must be the *original* normalized 1536, 768 and 384 images.
    Interpolation is deliberately outside this interface so that a backend
    cannot silently substitute a different resize kernel.
    """
    if len(pyramid) != 3:
        raise ValueError("Original SHARP requires exactly three pyramid levels")
    index = 0
    for level, (size, steps, stride) in enumerate(((1536, 5, 288), (768, 3, 192), (384, 1, 384))):
        image = pyramid[level]
        if image.shape != (1, 3, size, size) or not np.issubdtype(image.dtype, np.floating):
            raise ValueError(f"Level {level} must be normalized floating RGB [1,3,{size},{size}]")
        for row in range(steps):
            for column in range(steps):
                y, x = row * stride, column * stride
                yield Patch(index, level, row, column, image[..., y:y + PATCH_SIZE, x:x + PATCH_SIZE])
                index += 1


def _region(position: int, steps: int, padding: int) -> tuple[slice, slice]:
    start = padding if position else 0
    stop = TOKEN_GRID - padding if position < steps - 1 else TOKEN_GRID
    destination = 0 if position == 0 else TOKEN_GRID - padding + (position - 1) * (TOKEN_GRID - 2 * padding)
    return slice(start, stop), slice(destination, destination + stop - start)


class Accumulator:
    """Stitch each result immediately; do not retain all 35 encoder outputs."""

    def __init__(self, channels: int = 1024, dtype: np.dtype = np.dtype("float32")):
        if channels < 1 or not np.issubdtype(dtype, np.floating):
            raise ValueError("Features require positive channels and a floating dtype")
        self.channels = channels
        self.dtype = np.dtype(dtype)
        self.count = 0
        self.features = Features(
            *(np.empty((1, channels, size, size), dtype=self.dtype) for size in (96, 96, 96, 48, 24))
        )

    def _grid(self, value: np.ndarray, tokens: bool = False) -> np.ndarray:
        expected = (1, 577, self.channels) if tokens else (1, self.channels, TOKEN_GRID, TOKEN_GRID)
        if value.shape != expected or value.dtype != self.dtype:
            raise ValueError(f"Expected encoder output {expected}/{self.dtype}, got {value.shape}/{value.dtype}")
        # Block 5/11 outputs retain the class token; final_grid already removed it.
        return value[:, 1:, :].transpose(0, 2, 1).reshape(1, self.channels, TOKEN_GRID, TOKEN_GRID) if tokens else value

    @staticmethod
    def _write(destination: np.ndarray, grid: np.ndarray, row: int, column: int, steps: int, padding: int) -> None:
        sy, dy = _region(row, steps, padding)
        sx, dx = _region(column, steps, padding)
        destination[..., dy, dx] = grid[..., sy, sx]

    def add(self, patch: Patch, final_grid: np.ndarray, block5_tokens: np.ndarray, block11_tokens: np.ndarray) -> None:
        if patch.index != self.count or self.count >= PATCH_COUNT:
            raise ValueError("Encoder results must arrive once in original row-major order")
        if self.count < 25:
            level, row, column, steps, padding = 0, self.count // 5, self.count % 5, 5, 3
        elif self.count < 34:
            offset = self.count - 25
            level, row, column, steps, padding = 1, offset // 3, offset % 3, 3, 6
        else:
            level, row, column, steps, padding = 2, 0, 0, 1, 0
        if (patch.level, patch.row, patch.column) != (level, row, column):
            raise ValueError("Patch coordinates do not match the original pyramid")
        final_grid = self._grid(final_grid)
        if level == 0:
            first, second = self._grid(block5_tokens, True), self._grid(block11_tokens, True)
            self._write(self.features.latent0, first, row, column, steps, padding)
            self._write(self.features.latent1, second, row, column, steps, padding)
        destination = (self.features.high, self.features.middle, self.features.low)[level]
        self._write(destination, final_grid, row, column, steps, padding)
        self.count += 1

    def finish(self) -> Features:
        if self.count != PATCH_COUNT:
            raise ValueError(f"Incomplete original pyramid: {self.count}/{PATCH_COUNT} patches")
        return self.features


def encode_pyramid(
    pyramid: Sequence[np.ndarray],
    encode_patch: Callable[[np.ndarray], tuple[np.ndarray, np.ndarray, np.ndarray]],
    progress: Callable[[int, int], None] | None = None,
) -> Features:
    """Run the original encoder serially with bounded retained feature memory.

    Encoder tuple order: final_grid, block5_tokens, block11_tokens.
    At 1024 channels retained stitched output is 119.25 MiB in FP32
    (three 96x96 grids, one 48x48 grid, and one 24x24 grid),
    independent of the backend's temporary attention buffers.
    """
    accumulator = None
    for patch in patches(pyramid):
        output = encode_patch(np.ascontiguousarray(patch.pixels))
        if accumulator is None:
            accumulator = Accumulator(channels=output[0].shape[1], dtype=output[0].dtype)
        accumulator.add(patch, *output)
        if progress is not None:
            progress(patch.index + 1, PATCH_COUNT)
        del output
    assert accumulator is not None
    return accumulator.finish()
