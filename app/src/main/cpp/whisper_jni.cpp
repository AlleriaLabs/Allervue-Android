#include <jni.h>
#include <android/log.h>
#include "whisper.h"

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#define LOG_TAG "AllervueWhisper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
static uint16_t read_u16(const unsigned char *p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}
static uint32_t read_u32(const unsigned char *p) {
    return static_cast<uint32_t>(p[0]) |
        (static_cast<uint32_t>(p[1]) << 8) |
        (static_cast<uint32_t>(p[2]) << 16) |
        (static_cast<uint32_t>(p[3]) << 24);
}
static std::string srt_time(int64_t ms) {
    ms = std::max<int64_t>(0, ms);
    const int64_t hours = ms / 3600000; ms %= 3600000;
    const int64_t minutes = ms / 60000; ms %= 60000;
    const int64_t seconds = ms / 1000; ms %= 1000;
    char out[40];
    std::snprintf(out, sizeof(out), "%02lld:%02lld:%02lld,%03lld",
        static_cast<long long>(hours), static_cast<long long>(minutes),
        static_cast<long long>(seconds), static_cast<long long>(ms));
    return out;
}
static bool read_pcm_wav(const std::string &path, std::vector<float> &samples) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    unsigned char header[12];
    if (!in.read(reinterpret_cast<char *>(header), sizeof(header))) return false;
    if (std::string(reinterpret_cast<char *>(header), 4) != "RIFF" ||
        std::string(reinterpret_cast<char *>(header + 8), 4) != "WAVE") return false;

    uint16_t format = 0, channels = 0, bits = 0;
    uint32_t sample_rate = 0;
    bool have_fmt = false, have_data = false;
    std::streampos data_pos{};
    uint32_t data_size = 0;

    while (in && !(have_fmt && have_data)) {
        unsigned char chunk[8];
        if (!in.read(reinterpret_cast<char *>(chunk), sizeof(chunk))) break;
        const uint32_t size = read_u32(chunk + 4);
        const std::string id(reinterpret_cast<char *>(chunk), 4);
        const std::streampos payload = in.tellg();
        if (id == "fmt ") {
            std::vector<unsigned char> fmt(size);
            if (size < 16 || !in.read(reinterpret_cast<char *>(fmt.data()), size)) return false;
            format = read_u16(fmt.data());
            channels = read_u16(fmt.data() + 2);
            sample_rate = read_u32(fmt.data() + 4);
            bits = read_u16(fmt.data() + 14);
            have_fmt = true;
        } else if (id == "data") {
            data_pos = payload;
            data_size = size;
            in.seekg(size, std::ios::cur);
            have_data = true;
        } else {
            in.seekg(size, std::ios::cur);
        }
        if (size & 1U) in.seekg(1, std::ios::cur);
    }

    if (!have_fmt || !have_data || format != 1 || channels != 1 ||
        sample_rate != WHISPER_SAMPLE_RATE || bits != 16 || data_size < 2) return false;

    in.clear();
    in.seekg(data_pos);
    std::vector<int16_t> pcm(data_size / 2);
    if (!in.read(reinterpret_cast<char *>(pcm.data()), static_cast<std::streamsize>(pcm.size() * 2))) return false;
    samples.resize(pcm.size());
    for (size_t i = 0; i < pcm.size(); ++i) samples[i] = static_cast<float>(pcm[i]) / 32768.0f;
    return !samples.empty();
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_allerix_app_WhisperNative_transcribeWav(
    JNIEnv *env, jobject /* thiz */, jstring model_path, jstring wav_path,
    jstring language, jint requested_threads) {
    const char *model_chars = env->GetStringUTFChars(model_path, nullptr);
    const char *wav_chars = env->GetStringUTFChars(wav_path, nullptr);
    const char *language_chars = env->GetStringUTFChars(language, nullptr);
    const std::string model(model_chars ? model_chars : "");
    const std::string wav(wav_chars ? wav_chars : "");
    const std::string lang(language_chars ? language_chars : "auto");
    env->ReleaseStringUTFChars(model_path, model_chars);
    env->ReleaseStringUTFChars(wav_path, wav_chars);
    env->ReleaseStringUTFChars(language, language_chars);

    std::vector<float> audio;
    if (!read_pcm_wav(wav, audio)) {
        LOGE("Invalid or unsupported 16 kHz mono PCM WAV: %s", wav.c_str());
        return env->NewStringUTF("");
    }

    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(model.c_str(), context_params);
    if (!ctx) {
        LOGE("Unable to load Whisper model: %s", model.c_str());
        return env->NewStringUTF("");
    }

    const int threads = std::max(1, std::min(4, static_cast<int>(requested_threads)));
    const size_t chunk_samples = static_cast<size_t>(WHISPER_SAMPLE_RATE) * 30;
    std::string result;
    int cue_index = 1;

    for (size_t offset = 0; offset < audio.size(); offset += chunk_samples) {
        const size_t count = std::min(chunk_samples, audio.size() - offset);
        if (count < static_cast<size_t>(WHISPER_SAMPLE_RATE / 2)) break;

        whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = threads;
        params.translate = false;
        params.language = lang == "auto" ? "auto" : lang.c_str();
        params.detect_language = lang == "auto";
        params.no_context = true;
        params.single_segment = false;
        params.print_realtime = false;
        params.print_progress = false;
        params.print_timestamps = false;
        params.print_special = false;
        params.suppress_blank = true;
        params.suppress_non_speech_tokens = true;

        if (whisper_full(ctx, params, audio.data() + offset, static_cast<int>(count)) != 0) {
            LOGE("Whisper inference failed at sample offset %zu", offset);
            whisper_free(ctx);
            return env->NewStringUTF("");
        }

        const int n = whisper_full_n_segments(ctx);
        for (int i = 0; i < n; ++i) {
            const char *raw = whisper_full_get_segment_text(ctx, i);
            if (!raw) continue;
            std::string text(raw);
            const auto first = text.find_first_not_of(" \t\r\n");
            if (first == std::string::npos) continue;
            text = text.substr(first);
            const auto last = text.find_last_not_of(" \t\r\n");
            text.erase(last + 1);

            const int64_t base_ms = static_cast<int64_t>(offset) * 1000 / WHISPER_SAMPLE_RATE;
            const int64_t start_ms = base_ms + whisper_full_get_segment_t0(ctx, i) * 10;
            const int64_t end_ms = base_ms + whisper_full_get_segment_t1(ctx, i) * 10;
            if (end_ms <= start_ms) continue;
            result += std::to_string(cue_index++) + "\n";
            result += srt_time(start_ms) + " --> " + srt_time(end_ms) + "\n";
            result += text + "\n\n";
        }
    }

    whisper_free(ctx);
    return env->NewStringUTF(result.c_str());
}
