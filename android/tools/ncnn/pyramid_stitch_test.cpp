#include "pyramid_stitch.h"
#include <cstdio>
#include <functional>
#include <stdexcept>

static void rejects(const std::function<void()>& operation, int& count) {
    try { operation(); }
    catch (const std::runtime_error&) { ++count; return; }
    throw std::runtime_error("Invalid original-pyramid input was accepted");
}

int main() {
    try {
        int count = 0;
        const auto original = gemos::patch_at(0);
        std::vector<float> image(3 * 1536 * 1536), pixels, tokens(577 * 8), final(576 * 8);
        // Every publicly exposed descriptor field must be checked before reads.
        for (int field = 0; field < 8; ++field) {
            auto invalid = original;
            switch (field) {
                case 0: invalid.index = -1; break;
                case 1: invalid.level = 2; break;
                case 2: invalid.row = 999; break;
                case 3: invalid.column = -1; break;
                case 4: invalid.image_size = 1024; break;
                case 5: invalid.steps = 99; break;
                case 6: invalid.stride = 384; break;
                case 7: invalid.padding = 0; break;
            }
            rejects([&] { gemos::copy_patch(image, invalid, pixels); }, count);
            rejects([&] {
                gemos::Accumulator a(8);
                a.add(invalid, gemos::latent0, {tokens.data(), 8, true, 0});
            }, count);
        }
        rejects([&] { gemos::copy_patch({}, original, pixels); }, count);
        rejects([&] {
            gemos::Accumulator a(8);
            a.add(gemos::patch_at(1), gemos::latent0, {tokens.data(), 8, true, 0});
        }, count);
        rejects([&] {
            gemos::Accumulator a(8);
            a.add(original, gemos::latent0, {tokens.data(), 8, true, 0});
            a.add(original, gemos::latent0, {tokens.data(), 8, true, 0});
        }, count);
        rejects([&] {
            gemos::Accumulator a(8);
            a.add(original, gemos::latent0, {tokens.data(), 8, true, 0});
            a.add(original, gemos::high, {final.data(), 8, false, 576});
        }, count);
        rejects([&] {
            gemos::Accumulator a(8);
            a.add(original, gemos::latent0, {tokens.data(), 7, true, 0});
        }, count);
        rejects([&] {
            gemos::Accumulator a(8);
            a.add(original, gemos::latent0, {nullptr, 8, true, 0});
        }, count);
        rejects([&] {
            gemos::Accumulator a(8);
            a.add(original, gemos::latent0, {tokens.data(), 8, true, 0});
            a.add(original, gemos::latent1, {tokens.data(), 8, true, 0});
            a.add(original, gemos::high, {final.data(), 8, false, 575});
        }, count);
        rejects([&] { gemos::Accumulator(8).finish(); }, count);
        gemos::copy_patch(image, original, pixels);
        if (pixels.size() != 3 * 384 * 384) throw std::runtime_error("Valid patch copy failed");
        std::printf("{\"status\":\"pass\",\"invalidCasesRejected\":%d,\"validPatchElements\":%zu}\n", count, pixels.size());
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "Pyramid contract failed: %s\n", error.what()); return 1;
    }
}
