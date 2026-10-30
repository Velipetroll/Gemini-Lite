#include "gemini_renderer.h"
#include <android/log.h>
#include <cmath>
#include <vector>

#define LOG_TAG "GeminiRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const char* VERTEX_SHADER = R"glsl(#version 300 es
layout(location = 0) in vec2 aPosition;
out vec2 vUV;

void main() {
    vUV = aPosition * 0.5 + 0.5;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
)glsl";

// Ultra-optimized fragment shader engineered for mobile GPUs (Adreno / Mali)
// Uses mediump precision, precomputed basis vectors, and harmonic wave synthesis
static const char* FRAGMENT_SHADER = R"glsl(#version 300 es
precision mediump float;

in vec2 vUV;
out vec4 fragColor;

uniform vec2 uResolution;
uniform highp float uTime;
uniform vec4 uTouch;
uniform float uStarScale;
uniform float uGlowIntensity;
uniform vec4 uRotBasis; // cos, -sin, sin, cos
uniform vec2 uGradDir;  // cos(grad), sin(grad)
uniform vec2 uP1;
uniform vec2 uP2;
uniform vec2 uP3;

// Gemini official signature color palette
const vec3 BG_BASE       = vec3(0.0745, 0.0745, 0.0784); // #131314
const vec3 GEMINI_BLUE   = vec3(0.259, 0.522, 0.957);   // #4285F4
const vec3 GEMINI_PURPLE = vec3(0.608, 0.318, 0.878);   // #9B51E0
const vec3 GEMINI_PINK   = vec3(0.950, 0.380, 0.520);   // #FF6B8B
const vec3 GEMINI_CYAN   = vec3(0.350, 0.750, 1.000);   // #59C0F0

void main() {
    // Normalization with aspect ratio
    float aspect = uResolution.x / uResolution.y;
    vec2 p = (vUV - 0.5);
    p.x *= aspect;

    float t = uTime * 0.5;

    // --- 1. Ambient Cosmic Nebula (Fast Harmonic Domain Warping) ---
    // Fast analytical wave synthesis (Zero expensive noise loops)
    vec2 np = p - vec2(0.0, 0.22);
    float dist = dot(np, np); // Squared distance is faster than length()

    float w1 = sin(p.x * 2.8 + t * 0.4) * cos(p.y * 2.2 - t * 0.3);
    float w2 = sin(p.y * 3.5 + t * 0.3) * cos(p.x * 3.0 + t * 0.25);

    float nebula1 = clamp(1.0 - (dist * 1.8 + w1 * 0.15), 0.0, 1.0);
    float nebula2 = clamp(1.0 - (dist * 2.2 + w2 * 0.12), 0.0, 1.0);

    vec3 ambient = mix(BG_BASE, GEMINI_BLUE * 0.30, nebula1 * 0.4);
    ambient = mix(ambient, GEMINI_PURPLE * 0.38, nebula2 * 0.35);
    ambient = mix(ambient, GEMINI_PINK * 0.22, nebula1 * nebula2 * 0.35);

    // Touch ripple (optimized)
    if (uTouch.z > 0.02) {
        vec2 tp = (uTouch.xy / uResolution.xy - 0.5);
        tp.x *= aspect;
        float dTouch = length(p - tp);
        float ripple = sin(dTouch * 22.0 - uTime * 5.0) * 0.5 + 0.5;
        float rFade = clamp(1.0 - dTouch * 2.5, 0.0, 1.0);
        ambient += GEMINI_CYAN * (ripple * rFade * 0.12 * uTouch.z);
    }

    // --- 2. Central Gemini 4-Pointed Sparkle Star ---
    vec2 sp = p - vec2(0.0, 0.26);

    // If star is hidden (e.g. in deep chat mode), output early to save fill rate
    if (uStarScale < 0.02) {
        fragColor = vec4(ambient, 1.0);
        return;
    }

    float radius = 0.155 * uStarScale * (1.0 + 0.04 * sin(t * 2.2));
    float invRadius = 1.0 / max(radius, 0.001);

    // Matrix rotation via precalculated C++ uniform
    vec2 rotP = vec2(
        dot(sp, uRotBasis.xy),
        dot(sp, uRotBasis.zw)
    );

    vec2 q = abs(rotP);
    // 4-point astroid SDF
    float d = (sqrt(q.x) + sqrt(q.y)) - sqrt(radius);

    // Rotating gradient via precalculated C++ 2D basis (Zero atan!)
    float gFactor1 = clamp(dot(rotP, uGradDir) * invRadius * 0.8 + 0.5, 0.0, 1.0);
    float gFactor2 = clamp(dot(rotP, vec2(-uGradDir.y, uGradDir.x)) * invRadius * 0.8 + 0.5, 0.0, 1.0);

    vec3 starGrad = mix(GEMINI_BLUE, GEMINI_PURPLE, gFactor1);
    starGrad = mix(starGrad, GEMINI_PINK, gFactor2);
    starGrad = mix(starGrad, GEMINI_CYAN, 0.20);

    // Core glow (bright center)
    float coreDist = dot(sp, sp) * (invRadius * invRadius);
    float coreGlow = clamp(1.0 - coreDist * 1.5, 0.0, 1.0);
    starGrad = mix(starGrad, vec3(1.0), coreGlow * 0.65);

    // Antialiased star mask
    float starMask = smoothstep(0.004, -0.004, d);

    // Multi-layer Bloom (Fast polynomial falloff instead of pow)
    float b = clamp(1.0 - d * 3.6, 0.0, 1.0);
    float b2 = b * b;
    float bloom = b2 * b2 * 0.80 * uGlowIntensity;

    float spLen = length(sp);
    float radialHalo = (0.012 / (spLen + 0.035)) * uGlowIntensity;
    vec3 bloomCol = mix(GEMINI_BLUE, GEMINI_PURPLE, gFactor1) * 1.1;

    // --- 3. Micro Sparkles (Precalculated in C++ with 32-bit float accuracy) ---
    float sparkles = 0.0;
    sparkles += 0.0004 / (dot(p - uP1, p - uP1) + 0.0008);
    sparkles += 0.0004 / (dot(p - uP2, p - uP2) + 0.0008);
    sparkles += 0.0004 / (dot(p - uP3, p - uP3) + 0.0008);

    // Final color composition
    vec3 col = ambient;
    col += bloomCol * bloom;
    col += bloomCol * (radialHalo * 0.40);
    col += GEMINI_CYAN * (sparkles * 0.25 * uGlowIntensity);
    col = mix(col, starGrad, starMask);

    fragColor = vec4(col, 1.0);
}
)glsl";

GeminiRenderer::GeminiRenderer()
    : m_width(0),
      m_height(0),
      m_program(0),
      m_vao(0),
      m_vbo(0),
      m_locResolution(-1),
      m_locTime(-1),
      m_locTouch(-1),
      m_locStarScale(-1),
      m_locGlowIntensity(-1),
      m_locRotBasis(-1),
      m_locGradDir(-1),
      m_locP1(-1),
      m_locP2(-1),
      m_locP3(-1),
      m_starScale(1.0f),
      m_glowIntensity(1.0f),
      m_initialized(false) {}

GeminiRenderer::~GeminiRenderer() {
    destroy();
}

GLuint GeminiRenderer::compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    if (!shader) return 0;

    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 0) {
            std::vector<char> infoLog(infoLen);
            glGetShaderInfoLog(shader, infoLen, nullptr, infoLog.data());
            LOGE("Shader compilation error: %s", infoLog.data());
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

GLuint GeminiRenderer::createProgram(const char* vertexSource, const char* fragmentSource) {
    GLuint vShader = compileShader(GL_VERTEX_SHADER, vertexSource);
    if (!vShader) return 0;

    GLuint fShader = compileShader(GL_FRAGMENT_SHADER, fragmentSource);
    if (!fShader) {
        glDeleteShader(vShader);
        return 0;
    }

    GLuint program = glCreateProgram();
    if (!program) {
        glDeleteShader(vShader);
        glDeleteShader(fShader);
        return 0;
    }

    glAttachShader(program, vShader);
    glAttachShader(program, fShader);
    glLinkProgram(program);

    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        glDeleteProgram(program);
        program = 0;
    }

    glDeleteShader(vShader);
    glDeleteShader(fShader);
    return program;
}

bool GeminiRenderer::init(int width, int height) {
    LOGI("Optimized GeminiRenderer initialized for %dx%d", width, height);
    m_width = width;
    m_height = height;

    m_program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
    if (!m_program) return false;

    m_locResolution    = glGetUniformLocation(m_program, "uResolution");
    m_locTime          = glGetUniformLocation(m_program, "uTime");
    m_locTouch         = glGetUniformLocation(m_program, "uTouch");
    m_locStarScale     = glGetUniformLocation(m_program, "uStarScale");
    m_locGlowIntensity = glGetUniformLocation(m_program, "uGlowIntensity");
    m_locRotBasis      = glGetUniformLocation(m_program, "uRotBasis");
    m_locGradDir       = glGetUniformLocation(m_program, "uGradDir");
    m_locP1            = glGetUniformLocation(m_program, "uP1");
    m_locP2            = glGetUniformLocation(m_program, "uP2");
    m_locP3            = glGetUniformLocation(m_program, "uP3");

    static const GLfloat vertices[] = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f,
    };

    glGenVertexArrays(1, &m_vao);
    glBindVertexArray(m_vao);

    glGenBuffers(1, &m_vbo);
    glBindBuffer(GL_ARRAY_BUFFER, m_vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 2 * sizeof(GLfloat), nullptr);

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindVertexArray(0);

    m_initialized = true;
    glViewport(0, 0, m_width, m_height);
    return true;
}

void GeminiRenderer::resize(int width, int height) {
    m_width = width;
    m_height = height;
    glViewport(0, 0, m_width, m_height);
}

void GeminiRenderer::render(float timeSec, float touchX, float touchY, float touchPressure) {
    if (!m_initialized || !m_program) return;

    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(m_program);

    if (m_locResolution >= 0) {
        glUniform2f(m_locResolution, static_cast<float>(m_width), static_cast<float>(m_height));
    }
    if (m_locTime >= 0) {
        glUniform1f(m_locTime, timeSec);
    }
    if (m_locTouch >= 0) {
        glUniform4f(m_locTouch, touchX, touchY, touchPressure, timeSec);
    }
    if (m_locStarScale >= 0) {
        glUniform1f(m_locStarScale, m_starScale);
    }
    if (m_locGlowIntensity >= 0) {
        glUniform1f(m_locGlowIntensity, m_glowIntensity);
    }

    // Precalculate rotation matrix basis in C++: saves 2.5 million sin/cos per frame!
    if (m_locRotBasis >= 0) {
        float rotAngle = sinf(timeSec * 0.8f * 0.5f) * 0.06f;
        float c = cosf(rotAngle);
        float s = sinf(rotAngle);
        glUniform4f(m_locRotBasis, c, -s, s, c);
    }

    // Precalculate gradient direction vector in C++: saves 2.5 million atan per frame!
    if (m_locGradDir >= 0) {
        float gAngle = timeSec * 0.5f * 1.2f;
        glUniform2f(m_locGradDir, cosf(gAngle), sinf(gAngle));
    }

    // Precalculate particle orbits in C++ with full 32-bit CPU precision: zero GPU jitter
    float t = timeSec * 0.5f;
    if (m_locP1 >= 0) {
        glUniform2f(m_locP1, sinf(t * 0.7f) * 0.35f, 0.26f + cosf(t * 0.9f) * 0.18f);
    }
    if (m_locP2 >= 0) {
        glUniform2f(m_locP2, sinf(t * 0.8f + 2.1f) * 0.38f, 0.26f + cosf(t * 1.0f + 1.5f) * 0.20f);
    }
    if (m_locP3 >= 0) {
        glUniform2f(m_locP3, sinf(t * 0.6f + 4.2f) * 0.32f, 0.26f + cosf(t * 0.7f + 3.8f) * 0.16f);
    }

    glBindVertexArray(m_vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    glUseProgram(0);
}

void GeminiRenderer::destroy() {
    if (m_vbo) {
        glDeleteBuffers(1, &m_vbo);
        m_vbo = 0;
    }
    if (m_vao) {
        glDeleteVertexArrays(1, &m_vao);
        m_vao = 0;
    }
    if (m_program) {
        glDeleteProgram(m_program);
        m_program = 0;
    }
    m_initialized = false;
}
