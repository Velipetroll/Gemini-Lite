#include "gemini_renderer.h"
#include <android/log.h>
#include <cmath>
#include <vector>

#define LOG_TAG "GeminiRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const char* VERTEX_SHADER = R"glsl(#version 100
attribute vec2 aPosition;

uniform vec2 uHalfAspect;     // (0.5 * aspect, 0.5)
uniform vec4 uWaveOffset;     // (t*0.4, -t*0.3, t*0.3, t*0.25)
uniform vec4 uRotBasis;       // (cos, -sin, sin, cos)
uniform vec2 uScaledGradDir;  // (cos(g), sin(g)) * invRadius * 0.8

varying vec2 vP;
varying vec2 vNP;
varying vec2 vSP;
varying vec2 vRotP;
varying vec2 vGrad;
varying vec4 vWavePhase;

void main() {
    vec2 p = aPosition * uHalfAspect;
    vP = p;
    vNP = p - vec2(0.0, 0.22);

    vec2 sp = p - vec2(0.0, 0.26);
    vSP = sp;

    // Linear matrix rotation and gradient projection computed at 4 vertices instead of per-pixel
    vec2 rotP = vec2(dot(sp, uRotBasis.xy), dot(sp, uRotBasis.zw));
    vRotP = rotP;
    vGrad = vec2(
        dot(rotP, uScaledGradDir) + 0.5,
        dot(rotP, vec2(-uScaledGradDir.y, uScaledGradDir.x)) + 0.5
    );

    // Linear harmonic wave phases interpolated across the quad by GPU hardware
    vWavePhase = vec4(
        p.x * 2.8 + uWaveOffset.x,
        p.y * 2.2 + uWaveOffset.y,
        p.y * 3.5 + uWaveOffset.z,
        p.x * 3.0 + uWaveOffset.w
    );

    gl_Position = vec4(aPosition, 0.0, 1.0);
}
)glsl";

// Ultra-optimized fragment shader engineered for 2014+ mobile GPUs (Adreno 3xx / Mali-400+)
// Zero per-pixel divisions, zero per-pixel matrix rotations, spatial bounding-circle culling
static const char* FRAGMENT_SHADER = R"glsl(#version 100
precision mediump float;

varying vec2 vP;
varying vec2 vNP;
varying vec2 vSP;
varying vec2 vRotP;
varying vec2 vGrad;
varying vec4 vWavePhase;

uniform vec4 uTouch;      // (tp.x, tp.y, pressure, uTime * 5.0)
uniform vec4 uStarParams; // (starScale, sqrtRadius, invRadiusSq, glowIntensity)
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
    // --- 1. Ambient Cosmic Nebula (Interpolated phase synthesis) ---
    float dist = dot(vNP, vNP);

    float w1 = sin(vWavePhase.x) * cos(vWavePhase.y);
    float w2 = sin(vWavePhase.z) * cos(vWavePhase.w);

    float nebula1 = clamp(1.0 - (dist * 1.8 + w1 * 0.15), 0.0, 1.0);
    float nebula2 = clamp(1.0 - (dist * 2.2 + w2 * 0.12), 0.0, 1.0);

    vec3 ambient = mix(BG_BASE, GEMINI_BLUE * 0.30, nebula1 * 0.4);
    ambient = mix(ambient, GEMINI_PURPLE * 0.38, nebula2 * 0.35);
    ambient = mix(ambient, GEMINI_PINK * 0.22, nebula1 * nebula2 * 0.35);

    // Touch ripple (tp already normalized in C++)
    if (uTouch.z > 0.02) {
        float dTouch = length(vP - uTouch.xy);
        if (dTouch < 0.4) {
            float ripple = sin(dTouch * 22.0 - uTouch.w) * 0.5 + 0.5;
            float rFade = 1.0 - dTouch * 2.5;
            ambient += GEMINI_CYAN * (ripple * rFade * 0.12 * uTouch.z);
        }
    }

    // If star is hidden (in active chat mode), output early to save fill rate
    if (uStarParams.x < 0.02) {
        gl_FragColor = vec4(ambient, 1.0);
        return;
    }

    // --- 2. Central Gemini 4-Pointed Sparkle Star & Bloom ---
    float glowIntensity = uStarParams.w;
    float spSq = dot(vSP, vSP);
    float spLen = sqrt(spSq);
    float gFactor1 = clamp(vGrad.x, 0.0, 1.0);
    vec3 bloomCol = mix(GEMINI_BLUE, GEMINI_PURPLE, gFactor1) * 1.1;

    float radialHalo = (0.012 / (spLen + 0.035)) * glowIntensity;

    // --- 3. Micro Sparkles (Precalculated in C++ with 32-bit float accuracy) ---
    vec2 dp1 = vP - uP1;
    vec2 dp2 = vP - uP2;
    vec2 dp3 = vP - uP3;
    float sparkles = 0.0004 / (dot(dp1, dp1) + 0.0008)
                   + 0.0004 / (dot(dp2, dp2) + 0.0008)
                   + 0.0004 / (dot(dp3, dp3) + 0.0008);

    vec3 col = ambient + bloomCol * (radialHalo * 0.40) + GEMINI_CYAN * (sparkles * 0.25 * glowIntensity);

    vec2 q = abs(vRotP);
    float d = (sqrt(q.x) + sqrt(q.y)) - uStarParams.y;

    if (d < 0.2778) {
        float b = clamp(1.0 - d * 3.6, 0.0, 1.0);
        float b2 = b * b;
        col += bloomCol * (b2 * b2 * 0.80 * glowIntensity);

        if (d < 0.004) {
            float starMask = smoothstep(0.004, -0.004, d);
            float gFactor2 = clamp(vGrad.y, 0.0, 1.0);
            vec3 starGrad = mix(GEMINI_BLUE, GEMINI_PURPLE, gFactor1);
            starGrad = mix(starGrad, GEMINI_PINK, gFactor2);
            starGrad = mix(starGrad, GEMINI_CYAN, 0.20);

            float coreDist = spSq * uStarParams.z;
            float coreGlow = clamp(1.0 - coreDist * 1.5, 0.0, 1.0);
            starGrad = mix(starGrad, vec3(1.0), coreGlow * 0.65);

            col = mix(col, starGrad, starMask);
        }
    }

    gl_FragColor = vec4(col, 1.0);
}
)glsl";

GeminiRenderer::GeminiRenderer()
    : m_width(0),
      m_height(0),
      m_program(0),
      m_vbo(0),
      m_locHalfAspect(-1),
      m_locWaveOffset(-1),
      m_locRotBasis(-1),
      m_locScaledGradDir(-1),
      m_locTouch(-1),
      m_locStarParams(-1),
      m_locP1(-1),
      m_locP2(-1),
      m_locP3(-1),
      m_starScale(1.0f),
      m_glowIntensity(1.0f),
      m_aspect(1.0f),
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
    glBindAttribLocation(program, 0, "aPosition");
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
    LOGI("Optimized GeminiRenderer (GLES 2.0) initialized for %dx%d", width, height);
    m_width = width;
    m_height = height > 0 ? height : 1;
    m_aspect = static_cast<float>(m_width) / static_cast<float>(m_height);

    // Disable unneeded fixed-function stages to save mobile GPU bandwidth
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    glDisable(GL_DITHER);
    glDepthMask(GL_FALSE);

    m_program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
    if (!m_program) return false;

    m_locHalfAspect    = glGetUniformLocation(m_program, "uHalfAspect");
    m_locWaveOffset    = glGetUniformLocation(m_program, "uWaveOffset");
    m_locRotBasis      = glGetUniformLocation(m_program, "uRotBasis");
    m_locScaledGradDir = glGetUniformLocation(m_program, "uScaledGradDir");
    m_locTouch         = glGetUniformLocation(m_program, "uTouch");
    m_locStarParams    = glGetUniformLocation(m_program, "uStarParams");
    m_locP1            = glGetUniformLocation(m_program, "uP1");
    m_locP2            = glGetUniformLocation(m_program, "uP2");
    m_locP3            = glGetUniformLocation(m_program, "uP3");

    static const GLfloat vertices[] = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f,
    };

    glGenBuffers(1, &m_vbo);
    glBindBuffer(GL_ARRAY_BUFFER, m_vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);

    // Bind shader program and vertex buffer once (no redundant state switching per frame)
    glUseProgram(m_program);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 2 * sizeof(GLfloat), nullptr);

    if (m_locHalfAspect >= 0) {
        glUniform2f(m_locHalfAspect, 0.5f * m_aspect, 0.5f);
    }

    m_initialized = true;
    glViewport(0, 0, m_width, m_height);
    return true;
}

void GeminiRenderer::resize(int width, int height) {
    m_width = width;
    m_height = height > 0 ? height : 1;
    m_aspect = static_cast<float>(m_width) / static_cast<float>(m_height);
    glViewport(0, 0, m_width, m_height);
    if (m_initialized && m_locHalfAspect >= 0) {
        glUseProgram(m_program);
        glUniform2f(m_locHalfAspect, 0.5f * m_aspect, 0.5f);
    }
}

void GeminiRenderer::render(float timeSec, float touchX, float touchY, float touchPressure) {
    if (!m_initialized || !m_program) return;

    float t = timeSec * 0.5f;

    if (m_locWaveOffset >= 0) {
        glUniform4f(m_locWaveOffset, t * 0.4f, -t * 0.3f, t * 0.3f, t * 0.25f);
    }

    if (m_locTouch >= 0) {
        if (touchPressure > 0.02f && m_width > 0 && m_height > 0) {
            float tpX = (touchX / static_cast<float>(m_width) - 0.5f) * m_aspect;
            float tpY = (touchY / static_cast<float>(m_height) - 0.5f);
            glUniform4f(m_locTouch, tpX, tpY, touchPressure, timeSec * 5.0f);
        } else {
            glUniform4f(m_locTouch, 0.0f, 0.0f, 0.0f, 0.0f);
        }
    }

    if (m_starScale >= 0.02f) {
        float radius = 0.155f * m_starScale * (1.0f + 0.04f * sinf(t * 2.2f));
        float safeRadius = radius > 0.001f ? radius : 0.001f;
        float invRadius = 1.0f / safeRadius;
        float sqrtRadius = sqrtf(safeRadius);
        float invRadiusSq = invRadius * invRadius;

        if (m_locStarParams >= 0) {
            glUniform4f(m_locStarParams, m_starScale, sqrtRadius, invRadiusSq, m_glowIntensity);
        }

        if (m_locRotBasis >= 0) {
            float rotAngle = sinf(timeSec * 0.4f) * 0.06f;
            float c = cosf(rotAngle);
            float s = sinf(rotAngle);
            glUniform4f(m_locRotBasis, c, -s, s, c);
        }

        if (m_locScaledGradDir >= 0) {
            float gAngle = t * 1.2f;
            float scale = invRadius * 0.8f;
            glUniform2f(m_locScaledGradDir, cosf(gAngle) * scale, sinf(gAngle) * scale);
        }

        if (m_locP1 >= 0) {
            glUniform2f(m_locP1, sinf(t * 0.7f) * 0.35f, 0.26f + cosf(t * 0.9f) * 0.18f);
        }
        if (m_locP2 >= 0) {
            glUniform2f(m_locP2, sinf(t * 0.8f + 2.1f) * 0.38f, 0.26f + cosf(t * 1.0f + 1.5f) * 0.20f);
        }
        if (m_locP3 >= 0) {
            glUniform2f(m_locP3, sinf(t * 0.6f + 4.2f) * 0.32f, 0.26f + cosf(t * 0.7f + 3.8f) * 0.16f);
        }
    } else if (m_locStarParams >= 0) {
        glUniform4f(m_locStarParams, 0.0f, 0.0f, 0.0f, m_glowIntensity);
    }

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

void GeminiRenderer::destroy() {
    if (m_vbo) {
        glDeleteBuffers(1, &m_vbo);
        m_vbo = 0;
    }
    if (m_program) {
        glDeleteProgram(m_program);
        m_program = 0;
    }
    m_initialized = false;
}
