#ifndef GEMOS_ORIGINAL_PYRAMID_STITCH_H
#define GEMOS_ORIGINAL_PYRAMID_STITCH_H

#include <array>
#include <cstddef>
#include <stdexcept>
#include <vector>

// The unchanged 25+9+1 windows and feature merge from original_pyramid.py.
// This header has no ncnn dependency so its contract can be tested separately.
namespace gemos {
constexpr int patch_pixels = 384;
constexpr int token_grid = 24;
constexpr int patch_count = 35;

struct Patch {
    int index, level, row, column, image_size, steps, stride, padding;
};

inline Patch patch_at(int index) {
    if (index < 0 || index >= patch_count) throw std::runtime_error("Invalid original patch index");
    if (index < 25) return {index, 0, index / 5, index % 5, 1536, 5, 288, 3};
    if (index < 34) return {index, 1, (index - 25) / 3, (index - 25) % 3, 768, 3, 192, 6};
    return {index, 2, 0, 0, 384, 1, 384, 0};
}

inline void validate_patch(const Patch& p) {
    const Patch expected = patch_at(p.index);
    if (p.level != expected.level || p.row != expected.row || p.column != expected.column ||
        p.image_size != expected.image_size || p.steps != expected.steps ||
        p.stride != expected.stride || p.padding != expected.padding)
        throw std::runtime_error("Patch geometry differs from the original pyramid contract");
}

inline void copy_patch(const std::vector<float>& image, const Patch& p, std::vector<float>& patch) {
    validate_patch(p);
    const std::size_t plane = static_cast<std::size_t>(p.image_size) * p.image_size;
    if (image.size() != 3 * plane) throw std::runtime_error("Pyramid image shape mismatch");
    patch.resize(3 * patch_pixels * patch_pixels);
    for (int c = 0; c < 3; ++c)
        for (int y = 0; y < patch_pixels; ++y)
            for (int x = 0; x < patch_pixels; ++x)
                patch[(c * patch_pixels + y) * patch_pixels + x] =
                    image[c * plane + (p.row * p.stride + y) * p.image_size + p.column * p.stride + x];
}

struct Region { int source_start, source_stop, destination_start; };
inline Region region(int position, int steps, int padding) {
    const int start = position ? padding : 0;
    const int stop = position < steps - 1 ? token_grid - padding : token_grid;
    const int destination = position == 0 ? 0 : token_grid - padding + (position - 1) * (token_grid - 2 * padding);
    return {start, stop, destination};
}

enum Feature { latent0 = 0, latent1 = 1, high = 2, middle = 3, low = 4 };
inline const char* feature_name(int feature) {
    static const char* names[] = {"latent0", "latent1", "high", "middle", "low"};
    if (feature < 0 || feature >= 5) throw std::runtime_error("Invalid feature id");
    return names[feature];
}
inline int feature_size(int feature) { return feature < 3 ? 96 : feature == 3 ? 48 : 24; }

struct GridView {
    const float* values;
    int channels;
    bool tokens;
    std::size_t channel_stride;
    float at(int c, int y, int x) const {
        return tokens ? values[(1 + y * token_grid + x) * channels + c]
                      : values[c * channel_stride + y * token_grid + x];
    }
};

class Accumulator {
    int channels_, completed_ = 0, next_output_ = 0;
    std::array<std::vector<float>, 5> data_;
public:
    explicit Accumulator(int channels = 1024) : channels_(channels) {
        if (channels < 1) throw std::runtime_error("Invalid feature channels");
        for (int k = 0; k < 5; ++k) {
            const int size = feature_size(k);
            data_[k].resize(static_cast<std::size_t>(channels) * size * size);
        }
    }
    void add(const Patch& p, Feature feature, GridView view) {
        validate_patch(p);
        const Patch expected = patch_at(completed_);
        if (p.index != completed_ || p.level != expected.level || p.row != expected.row || p.column != expected.column)
            throw std::runtime_error("Original patches must arrive once in row-major order");
        const int required = p.level == 0 ? next_output_ : p.level == 1 ? middle : low;
        if (feature != required || view.channels != channels_ || view.tokens != (feature < high) ||
            !view.values || (!view.tokens && view.channel_stride < token_grid * token_grid))
            throw std::runtime_error("Feature order/layout differs from the original pyramid contract");
        const Region y_region = region(p.row, p.steps, p.padding), x_region = region(p.column, p.steps, p.padding);
        const int size = feature_size(feature);
        auto& destination = data_[feature];
        for (int c = 0; c < channels_; ++c)
            for (int sy = y_region.source_start; sy < y_region.source_stop; ++sy)
                for (int sx = x_region.source_start; sx < x_region.source_stop; ++sx) {
                    const int dy = y_region.destination_start + sy - y_region.source_start;
                    const int dx = x_region.destination_start + sx - x_region.source_start;
                    destination[(static_cast<std::size_t>(c) * size + dy) * size + dx] = view.at(c, sy, sx);
                }
        if (p.level == 0 && next_output_ < 2) ++next_output_;
        else { ++completed_; next_output_ = 0; }
    }
    const std::array<std::vector<float>, 5>& finish() const {
        if (completed_ != patch_count || next_output_ != 0) throw std::runtime_error("Incomplete original 35-patch pyramid");
        return data_;
    }
    int completed() const { return completed_; }
    std::size_t retained_bytes() const {
        std::size_t total = 0;
        for (const auto& feature : data_) total += feature.size() * sizeof(float);
        return total;
    }
};
} // namespace gemos
#endif
