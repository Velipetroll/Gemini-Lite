#pragma once

#include <GLES3/gl3.h>

class GeminiRenderer {
public:
    GeminiRenderer();
    ~GeminiRenderer();

    bool init(int width, int height);
    void resize(int width, int height);
    void render(float timeSec, float touchX, float touchY, float touchPressure);
    void destroy();

    void setStarScale(float scale) { m_starScale = scale; }
    void setGlowIntensity(float intensity) { m_glowIntensity = intensity; }

private:
    GLuint compileShader(GLenum type, const char* source);
    GLuint createProgram(const char* vertexSource, const char* fragmentSource);

    int m_width;
    int m_height;

    GLuint m_program;
    GLuint m_vao;
    GLuint m_vbo;

    // Optimized uniform locations
    GLint m_locResolution;
    GLint m_locTime;
    GLint m_locTouch;
    GLint m_locStarScale;
    GLint m_locGlowIntensity;
    GLint m_locRotBasis;
    GLint m_locGradDir;
    GLint m_locP1;
    GLint m_locP2;
    GLint m_locP3;

    float m_starScale;
    float m_glowIntensity;
    bool m_initialized;
};
