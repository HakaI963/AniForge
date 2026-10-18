// forge-console — ImGui viewer for the AniForge live edit stream
// (Watch Studio API SSE + timeline preview + FX panel + OpenCode prompt.)

#include "imgui.h"
#include "imgui_impl_glfw.h"
#include "imgui_impl_opengl3.h"
#include <GLFW/glfw3.h>
#include <nlohmann/json.hpp>
#include <curl/curl.h>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <deque>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

using json = nlohmann::json;

struct ClipUi {
    std::string id;
    int layer = 0;
    float position = 0.0f;
    float start = 0.0f;
    float end = 0.0f;
    float speed = 1.0f;
    float duration() const { return (end - start) / (speed <= 0.0f ? 1.0f : speed); }
};

struct App {
    char base[256] = "http://127.0.0.1:8787";
    char project[512] = "project.osp";
    char opsFile[512] = "ops.json";
    char prompt[1024] = "";
    char selectedClip[64] = "c1";
    char hex[16] = "#00FF00";
    char text[80] = "第1話";
    char ratio[8] = "4:5";

    int renderPreset = 0;
    int renderJobState = 0;
    std::string renderJobId;
    std::string renderMessage;

    std::deque<std::string> log;
    std::vector<ClipUi> clips;
    std::atomic<bool> running{true};
};

static App* g_app = nullptr;
static std::mutex g_app_mut;

static void app_log(const std::string& s) {
    if (!g_app) return;
    std::lock_guard<std::mutex> lk(g_app_mut);
    g_app->log.push_back(s);
    if (g_app->log.size() > 600) g_app->log.pop_front();
}

static void load_timeline_json() {
    std::ifstream f(g_app->project);
    if (!f) return;
    std::stringstream ss; ss << f.rdbuf();
    std::string data = ss.str();
    if (data.empty()) return;
    json j;
    try { j = json::parse(data); } catch (...) {
        app_log("[load] project.json parse failed");
        return;
    }
    std::vector<ClipUi> clips;
    if (j.contains("clips")) {
        for (auto& cj : j["clips"]) {
            ClipUi c;
            c.id = cj.value("id", "?");
            c.layer = cj.value("layer", 0);
            c.position = cj.value("position", 0.0f);
            c.start = cj.value("start", 0.0f);
            c.end = cj.value("end", 0.0f);
            c.speed = cj.value("speed", 1.0f);
            clips.push_back(c);
        }
    }
    std::lock_guard<std::mutex> lk(g_app_mut);
    g_app->clips = std::move(clips);
}

// ------------------------------------------------------------------ HTTP
struct CurlSink {
    std::string buf;
};

static size_t write_cb(char* ptr, size_t size, size_t nmemb, void* userdata) {
    CurlSink* sink = static_cast<CurlSink*>(userdata);
    sink->buf.append(ptr, size * nmemb);
    return size * nmemb;
}

static std::string http_call(const char* method, const std::string& url,
                             const std::string& body, std::string* out) {
    CURL* c = curl_easy_init();
    if (!c) { if (out) *out = ""; return "curl init failed"; }
    struct curl_slist* headers = nullptr;
    headers = curl_slist_append(headers, "Content-Type: application/json");
    curl_easy_setopt(c, CURLOPT_URL, url.c_str());
    curl_easy_setopt(c, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(c, CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(c, CURLOPT_NOPROGRESS, 1L);
    curl_easy_setopt(c, CURLOPT_CONNECTTIMEOUT, 4L);
    curl_easy_setopt(c, CURLOPT_WRITEFUNCTION, write_cb);
    CurlSink sink;
    curl_easy_setopt(c, CURLOPT_WRITEDATA, &sink);
    if (body.empty()) curl_easy_setopt(c, CURLOPT_HTTPGET, 1L);
    else { curl_easy_setopt(c, CURLOPT_POSTFIELDS, body.c_str()); }
    CURLcode rc = curl_easy_perform(c);
    long code = 0;
    curl_easy_getinfo(c, CURLINFO_RESPONSE_CODE, &code);
    if (out) *out = sink.buf;
    curl_easy_cleanup(c);
    std::stringstream ss;
    ss << "http " << code;
    if (rc != CURLE_OK) ss << " (" << curl_easy_strerror(rc) << ")";
    return ss.str();
}

static size_t write_cb_plain(char* ptr, size_t size, size_t nmemb, void* userdata) {
    CurlSink* sink = static_cast<CurlSink*>(userdata);
    sink->buf.append(ptr, size * nmemb);
    size_t pos;
    while ((pos = sink->buf.find('\n')) != std::string::npos) {
        std::string line = sink->buf.substr(0, pos);
        sink->buf.erase(0, pos + 1);
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.rfind("data: ", 0) == 0) {
            app_log(std::string("⟨") + line.substr(6) + "⟩");
        }
    }
    return size * nmemb;
}

// SSE worker (attached once at startup; reconnects with backoff)
static void sse_thread(std::string base) {
    while (g_app->running) {
        CURL* c = curl_easy_init();
        if (!c) return;
        struct curl_slist* h = curl_slist_append(nullptr, "Accept: text/event-stream");
        curl_easy_setopt(c, CURLOPT_URL, (base + std::string("/events")).c_str());
        curl_easy_setopt(c, CURLOPT_HTTPHEADER, h);
        curl_easy_setopt(c, CURLOPT_FOLLOWLOCATION, 1L);
        curl_easy_setopt(c, CURLOPT_CONNECTTIMEOUT, 4L);
        curl_easy_setopt(c, CURLOPT_WRITEFUNCTION, write_cb_plain);
        CurlSink sink;
        curl_easy_setopt(c, CURLOPT_WRITEDATA, &sink);
        curl_easy_perform(c);
        curl_easy_cleanup(c);
        curl_slist_free_all(h);
        app_log("[sse reconnect]");
        std::this_thread::sleep_for(std::chrono::seconds(2));
    }
}

static void apply_ops_json(const json& ops) {
    std::ifstream f(g_app->project);
    if (!f) { app_log("[apply] project file missing"); return; }
    std::stringstream ss; ss << f.rdbuf();
    json j;
    try { j = json::parse(ss.str()); } catch (...) {
        app_log("[apply] project.json parse failed");
        return;
    }
    json out = json::object();
    out["project"] = j;
    out["ops"] = ops;
    out["message"] = "forge-console fx";
    std::string resp;
    std::string st = http_call("POST", std::string(g_app->base) + "/edit/apply", out.dump(), &resp);
    app_log("[apply] " + st + " " + resp.substr(0, 220));
    try {
        json r = json::parse(resp);
        if (r.contains("project")) {
            std::ofstream wf(g_app->project);
            wf << r["project"].dump();
            app_log("[saved] " + std::string(g_app->project));
        }
    } catch (...) { app_log("[apply] response not json — not overwriting"); }
    load_timeline_json();
}

static void send_intents(const std::string& kind, float p1, float p2) {
    (void)p2;
    json ops = json::array();
    json one = json::object();
    std::string clip(g_app->selectedClip);
    if (kind == "cut") {
        one["op"] = "cut"; one["clip_id"] = clip; one["at"] = p1;
    } else if (kind == "speed") {
        one["op"] = "speed"; one["clip_id"] = clip; one["speed"] = p1;
    } else if (kind == "chroma") {
        one["op"] = "add_effect"; one["clip_id"] = clip;
        one["effect"] = {{"type", "ChromaKey"},
                         {"params", {{"background", std::string(g_app->hex)},
                                     {"tolerance", p1}, {"softness", p1 * 0.3f}}}};
    } else if (kind == "text") {
        one["op"] = "set_text"; one["clip_id"] = clip;
        one["text"] = {{"content", std::string(g_app->text)},
                       {"style", "anime"}, {"position", "center"}};
    } else if (kind == "glow") {
        one["op"] = "add_effect"; one["clip_id"] = clip;
        one["effect"] = {{"type", "Glow"}, {"params", {{"intensity", p1}}}};
    } else if (kind == "crop") {
        one["op"] = "crop"; one["clip_id"] = clip; one["ratio"] = std::string(g_app->ratio);
    } else if (kind == "flash") {
        one["op"] = "flash_frame"; one["at"] = p1; one["duration"] = 0.05;
    }
    ops.push_back(one);
    apply_ops_json(ops);
}

static void run_render() {
    std::ifstream f(g_app->project);
    if (!f) { app_log("[render] project.osp missing"); g_app->renderJobState = 4; return; }
    std::stringstream ss; ss << f.rdbuf();
    json out;
    try { out["project"] = json::parse(ss.str()); }
    catch (...) { app_log("[render] project.json parse failed"); g_app->renderJobState = 4; return; }
    static const char* kPresets[3] = {nullptr, "shonen-flash", "anime-studio-standard"};
    if (g_app->renderPreset > 0) out["preset"] = kPresets[g_app->renderPreset];
    std::string resp;
    std::string st = http_call("POST", std::string(g_app->base) + "/project/render", out.dump(), &resp);
    app_log("[render] " + st + " " + resp.substr(0, 220));
    try {
        json r = json::parse(resp);
        g_app->renderJobId = r.value("job_id", "?");
        g_app->renderJobState = 1;
    } catch (...) { g_app->renderJobState = 4; g_app->renderMessage = "parse error"; }
}

static void poll_render() {
    if (g_app->renderJobState != 1 && g_app->renderJobState != 2) return;
    static std::chrono::steady_clock::time_point last;
    auto now = std::chrono::steady_clock::now();
    if (std::chrono::duration_cast<std::chrono::milliseconds>(now - last).count() < 1000) return;
    last = now;
    std::string url = std::string(g_app->base) + "/render/" + g_app->renderJobId;
    std::string resp;
    http_call("GET", url, "", &resp);
    try {
        json r = json::parse(resp);
        if (r.value("status", "") == "done") {
            g_app->renderJobState = 3;
            g_app->renderMessage = r.value("output", "");
        } else g_app->renderJobState = 2;
    } catch (...) { g_app->renderJobState = 4; }
}

// ------------------------------------------------------------------ UI
static ImVec4 colorFor(int layer) {
    switch (layer) {
        case 0: return {0.55f, 0.64f, 1.00f, 1.0f};
        case 1: return {1.00f, 0.55f, 0.85f, 1.0f};
        case 2: return {0.55f, 1.00f, 0.72f, 1.0f};
        case 3: return {1.00f, 0.88f, 0.42f, 1.0f};
        case 4: return {0.78f, 0.55f, 1.00f, 1.0f};
        default: return {0.80f, 0.80f, 0.80f, 1.0f};
    }
}

static void timeline_window() {
    if (!ImGui::Begin("Timeline", nullptr)) { ImGui::End(); return; }
    ImGui::TextDisabled("Loaded from %s", g_app->project);
    int maxlayer = 0; float maxEnd = 10.0f;
    {
        std::lock_guard<std::mutex> lk(g_app_mut);
        for (auto& c : g_app->clips) {
            maxlayer = std::max(maxlayer, c.layer);
            maxEnd = std::max(maxEnd, c.position + c.duration());
        }
    }
    const float ROW = 20.0f; const float PAD = 4.0f;
    const float scale = (ImGui::GetContentRegionAvail().x - 8.0f) / std::max(1.0f, maxEnd);
    ImVec2 base = ImGui::GetCursorScreenPos();
    ImDrawList* dl = ImGui::GetWindowDrawList();
    float y = base.y;
    for (int l = 0; l <= maxlayer; ++l) {
        dl->AddRectFilled({base.x, y}, {base.x + ImGui::GetContentRegionAvail().x, y + ROW},
                          IM_COL32(250, 250, 255, 12), 3.0f);
        dl->AddText({base.x + 2, y + PAD}, IM_COL32(220, 220, 220, 240),
                    ("V" + std::to_string(l + 1)).c_str());
        y += ROW + PAD;
    }
    {
        std::lock_guard<std::mutex> lk(g_app_mut);
        for (auto& c : g_app->clips) {
            const float x0 = base.x + c.position * scale;
            const float x1 = base.x + (c.position + c.duration()) * scale;
            const float yy = base.y + c.layer * (ROW + PAD);
            dl->AddRectFilled({x0, yy}, {std::max(x0 + 4.0f, x1), yy + ROW},
                              ImGui::ColorConvertFloat4ToU32(colorFor(c.layer)), 3.0f);
            dl->AddText({x0 + 3, yy + PAD}, IM_COL32(20, 20, 20, 220), c.id.c_str());
        }
    }
    ImGui::Dummy(ImVec2(0, (maxlayer + 1) * (ROW + PAD) + 6));
    if (ImGui::Button("Reload timeline")) load_timeline_json();
    ImGui::SameLine(); ImGui::TextDisabled("V1 at bottom, V9 at top");
    ImGui::End();
}

static void feed_window() {
    if (!ImGui::Begin("Agent Feed", nullptr)) { ImGui::End(); return; }
    ImGui::SameLine();
    if (ImGui::Button("Clear")) { std::lock_guard<std::mutex> lk(g_app_mut); g_app->log.clear(); }
    ImGui::SameLine(); ImGui::TextDisabled("(SSE /events)");
    {
        std::lock_guard<std::mutex> lk(g_app_mut);
        ImGui::BeginChild("scroller", ImVec2(0, 0), false);
        for (auto& s : g_app->log) ImGui::TextWrapped("• %s", s.c_str());
        if (ImGui::GetScrollY() >= ImGui::GetScrollMaxY() - 4)
            ImGui::SetScrollHereY(1.0f);
        ImGui::EndChild();
    }
    ImGui::End();
}

static void config_window() {
    if (!ImGui::Begin("Setup", nullptr)) { ImGui::End(); return; }
    ImGui::InputText("Studio URL", g_app->base, sizeof(g_app->base));
    if (ImGui::IsItemDeactivatedAfterEdit()) {
        app_log("[config] base changed — SSE uses new URL on next reconnect");
    }
    ImGui::InputText("project.osp", g_app->project, sizeof(g_app->project));
    if (ImGui::IsItemDeactivatedAfterEdit()) load_timeline_json();
    ImGui::InputText("ops.json", g_app->opsFile, sizeof(g_app->opsFile));
    ImGui::InputText("Select clip", g_app->selectedClip, sizeof(g_app->selectedClip));
    ImGui::Separator();
    if (ImGui::Button("Spawn opencode run")) {
        std::string cmd = "opencode run \"AniForge session: edit " + std::string(g_app->project) +
                          " through the studio API.\"";
        app_log("[spawn] " + cmd);
        std::system((cmd + " 2>&1 | tee afctl.log").c_str());
    }
    ImGui::Separator();
    ImGui::TextWrapped("The console edits nothing by itself — it edits through the Studio API.\n"
                       "afctl and the OpenCode agent edit the same project.osp. Everything is logged.");
    ImGui::End();
}

static void fx_window() {
    if (!ImGui::Begin("FX panel", nullptr)) { ImGui::End(); return; }
    static float cut_t = 2.5f, speed = 1.5f, chroma_tol = 0.18f, flash_t = 8.0f, glow_i = 0.55f;

    if (ImGui::Button("Cut")) send_intents("cut", cut_t, 0);
    ImGui::SameLine(); ImGui::SetNextItemWidth(90); ImGui::InputFloat("at##cut", &cut_t);

    ImGui::SameLine();
    if (ImGui::Button("Speed")) send_intents("speed", speed, 0);
    ImGui::SetNextItemWidth(90); ImGui::SliderFloat("speed", &speed, 0.2f, 8.0f, "x%.2f");

    if (ImGui::Button("Chroma key")) send_intents("chroma", chroma_tol, 0);
    ImGui::SameLine(); ImGui::InputText("##hex", g_app->hex, sizeof(g_app->hex));
    ImGui::SliderFloat("tol", &chroma_tol, 0.02f, 0.60f);

    if (ImGui::Button("JP text card")) send_intents("text", 0, 0);
    ImGui::SameLine(); ImGui::InputText("##text", g_app->text, sizeof(g_app->text));

    if (ImGui::Button("Flash frame")) send_intents("flash", flash_t, 0);
    ImGui::SameLine(); ImGui::SliderFloat("##ft", &flash_t, 0.0f, 20.0f);

    if (ImGui::Button("Glow")) send_intents("glow", glow_i, 0);
    ImGui::SameLine(); ImGui::SliderFloat("##gi", &glow_i, 0.0f, 1.0f);

    static int cropIdx = 1;
    const char* ratios[4] = {"1:1", "4:5", "9:16", "16:9"};
    ImGui::Combo("crop ratio", &cropIdx, ratios, 4);
    if (ImGui::Button("Apply crop")) {
        std::strncpy(g_app->ratio, ratios[cropIdx], sizeof(g_app->ratio) - 1);
        send_intents("crop", 0, 0);
    }

    ImGui::Separator();
    const char* presets[3] = {"—", "shonen-flash", "anime-studio-standard"};
    ImGui::Combo("render preset", &g_app->renderPreset, presets, 3);
    if (ImGui::Button("Render")) { g_app->renderJobState = 0; run_render(); }
    if (g_app->renderJobState == 1 || g_app->renderJobState == 2)
        ImGui::SameLine(), ImGui::Text("job %s", g_app->renderJobId.c_str());
    else if (g_app->renderJobState == 3)
        ImGui::TextColored({0.4f, 1.0f, 0.4f, 1}, "done %s", g_app->renderMessage.c_str());
    else if (g_app->renderJobState == 4)
        ImGui::TextColored({1.0f, 0.4f, 0.4f, 1}, "failed %s", g_app->renderMessage.c_str());

    ImGui::End();
}

static void chat_window() {
    if (!ImGui::Begin("Prompt → OpenCode", nullptr)) { ImGui::End(); return; }
    bool entered = ImGui::InputTextMultiline("##prompt", g_app->prompt, sizeof(g_app->prompt), ImVec2(-1, 80),
                              ImGuiInputTextFlags_EnterReturnsTrue);
    if (ImGui::Button("Send to OpenCode") || entered) {
        std::string p(g_app->prompt);
        if (!p.empty()) {
            // escape quotes simply
            std::string esc;
            for (char ch : p) {
                if (ch == '"' || ch == '\\') esc.push_back('\\');
                esc.push_back(ch);
            }
            std::string cmd = "opencode run \"" + esc + "\" 2>&1 | tee afctl.log";
            app_log("[spawn] " + cmd);
            std::thread([cmd]() { int rc = std::system(cmd.c_str()); (void)rc;
                                  app_log("[opencode] finished"); }).detach();
            memset(g_app->prompt, 0, sizeof(g_app->prompt));
        }
    }
    ImGui::TextDisabled("Manual: afctl apply --file %s --ops %s", g_app->project, g_app->opsFile);
    ImGui::End();
}

int main() {
    if (!glfwInit()) return 1;
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
#ifdef __APPLE__
    glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE);
#endif
    GLFWwindow* w = glfwCreateWindow(1240, 800, "forge-console", nullptr, nullptr);
    if (!w) { glfwTerminate(); return 1; }
    glfwMakeContextCurrent(w);
    glfwSwapInterval(1);

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGui_ImplGlfw_InitForOpenGL(w, true);
    ImGui_ImplOpenGL3_Init("#version 130 core");
    ImGui::StyleColorsDark();

    App app; g_app = &app;
    load_timeline_json();
    std::thread sse(sse_thread, std::string(g_app->base));
    sse.detach();

    while (!glfwWindowShouldClose(w)) {
        glfwPollEvents();
        ImGui_ImplOpenGL3_NewFrame();
        ImGui_ImplGlfw_NewFrame();
        ImGui::NewFrame();

        ImGui::SetNextWindowPos(ImVec2(10, 10), ImGuiCond_FirstUseEver);
        ImGui::SetNextWindowSize(ImVec2(340, 240), ImGuiCond_FirstUseEver);
        config_window();

        ImGui::SetNextWindowPos(ImVec2(10, 260), ImGuiCond_FirstUseEver);
        ImGui::SetNextWindowSize(ImVec2(340, 340), ImGuiCond_FirstUseEver);
        feed_window();

        ImGui::SetNextWindowPos(ImVec2(360, 10), ImGuiCond_FirstUseEver);
        ImGui::SetNextWindowSize(ImVec2(420, 280), ImGuiCond_FirstUseEver);
        timeline_window();

        ImGui::SetNextWindowPos(ImVec2(790, 10), ImGuiCond_FirstUseEver);
        ImGui::SetNextWindowSize(ImVec2(440, 480), ImGuiCond_FirstUseEver);
        fx_window();

        ImGui::SetNextWindowPos(ImVec2(360, 300), ImGuiCond_FirstUseEver);
        ImGui::SetNextWindowSize(ImVec2(420, 300), ImGuiCond_FirstUseEver);
        chat_window();

        poll_render();

        ImGui::Render();
        int dw, dh; glfwGetFramebufferSize(w, &dw, &dh);
        glViewport(0, 0, dw, dh);
        ImVec4 clear = {0.082f, 0.086f, 0.11f, 1.0f};
        glClearColor(clear.x, clear.y, clear.z, clear.w);
        glClear(GL_COLOR_BUFFER_BIT);
        ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
        glfwSwapBuffers(w);
    }

    g_app->running = false;
    ImGui_ImplOpenGL3_Shutdown();
    ImGui_ImplGlfw_Shutdown();
    ImGui::DestroyContext();
    glfwDestroyWindow(w);
    glfwTerminate();
    return 0;
}
