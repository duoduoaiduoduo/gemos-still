#!/usr/bin/env python3
"""Package reviewed native stage artifacts as installed, read-only APK code.

Model weights and photos are never part of this bundle. Each input artifact has
an upstream build-produced SHA256SUMS file; no downloaded executable is loaded
from an app-writable directory.
"""
import argparse
import hashlib
import shutil
import struct
import zipfile
from pathlib import Path

BUNDLES = {
    'Gemos-original-patch-probe-arm64': {
        'gemos-patch-probe': 'libgemos-patch-probe.so',
        'gemos-pyramid-probe': 'libgemos-pyramid-probe.so',
    },
    'Gemos-original-rest-probe-arm64': {
        'gemos-rest-probe': 'libgemos-rest-probe.so',
        'libMNN.so': 'libMNN.so',
        'libc++_shared.so': 'libc++_shared.so',
    },
}
EXPECTED = {target for files in BUNDLES.values() for target in files.values()}


def elf(data, name):
    if data[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<HH', data, 16) != (3, 183):
        raise ValueError(f'{name}: expected little-endian ARM64 ET_DYN installed code')
    offset = struct.unpack_from('<Q', data, 32)[0]
    stride, count = struct.unpack_from('<HH', data, 54)
    loads = []
    for i in range(count):
        header = offset + i * stride
        if struct.unpack_from('<I', data, header)[0] == 1:
            alignment = struct.unpack_from('<Q', data, header + 48)[0]
            if alignment < 16384:
                raise ValueError(f'{name}: native LOAD alignment is below 16 KiB')
            loads.append(alignment)
    if not loads:
        raise ValueError(f'{name}: no ELF load segments')


def package(source, main):
    native = main / 'jniLibs' / 'arm64-v8a'
    notices = main / 'assets' / 'original-native-licenses'
    native.mkdir(parents=True, exist_ok=True)
    notices.mkdir(parents=True, exist_ok=True)
    for bundle, files in BUNDLES.items():
        folder = source / bundle
        manifest = {}
        for line in (folder / 'SHA256SUMS.txt').read_text().splitlines():
            digest, name = line.split(maxsplit=1)
            manifest[name.lstrip('*')] = digest
        for name, target in files.items():
            data = (folder / name).read_bytes()
            if hashlib.sha256(data).hexdigest() != manifest.get(name):
                raise ValueError(f'{bundle}/{name}: build artifact hash mismatch')
            elf(data, name)
            (native / target).write_bytes(data)
            (native / target).chmod(0o755)
        destination = notices / bundle
        destination.mkdir(exist_ok=True)
        for path in folder.iterdir():
            if path.is_file() and (path.suffix in {'.txt', '.json', '.patch'}):
                shutil.copy2(path, destination / path.name)
    print('Packaged five verified native files and their license notices; no models included.')


def verify(apk):
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        for name in EXPECTED:
            path = 'lib/arm64-v8a/' + name
            if path not in names:
                raise ValueError(f'APK is missing installed code: {name}')
            elf(archive.read(path), name)
            if archive.getinfo(path).compress_type == zipfile.ZIP_STORED:
                raise ValueError(f'{name}: expected compressed libraries for installation extraction')
        if not any(name.startswith('assets/original-native-licenses/') for name in names):
            raise ValueError('APK is missing native license notices')
        if any(name.endswith(('.mnn', '.mnn.weight', '.onnx.data', '.ncnn.bin')) for name in names):
            raise ValueError('Original model weights must not be embedded in this APK')
    print('APK contains all five ARM64 native files, 16 KiB ELF alignment, and license notices.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', nargs='?', type=Path)
    parser.add_argument('main', nargs='?', type=Path)
    parser.add_argument('--verify-apk', type=Path)
    args = parser.parse_args()
    if args.verify_apk:
        verify(args.verify_apk)
    elif args.source and args.main:
        package(args.source, args.main)
    else:
        parser.error('provide artifact source and app main directory, or --verify-apk')


if __name__ == '__main__':
    main()
