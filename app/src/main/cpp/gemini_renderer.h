#pragma once

#include <GLES2/gl2.h>

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
    GLuint m_vbo;

    // Optimized uniform locations (vertex + fragment)
    GLint m_locHalfAspect;
    GLint m_locWaveOffset;
    GLint m_locRotBasis;
    GLint m_locScaledGradDir;
    GLint m_locTouch;
    GLint m_locStarParams; // x=starScale, y=sqrtRadius, z=invRadiusSq, w=glowIntensity
    GLint m_locP1;
    GLint m_locP2;
    GLint m_locP3;

    float m_starScale;
    float m_glowIntensity;
    float m_aspect;
    bool m_initialized;
};
