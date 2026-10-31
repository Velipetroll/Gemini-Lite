#include "gemini_api.h"
#include <cctype>
#include <cstdio>
#include <cstdlib>

bool GeminiApiClient::validateApiKeyFormat(const std::string& apiKey) {
    if (apiKey.length() < 30) {
        return false;
    }
    if (apiKey.rfind("AIzaSy", 0) != 0) {
        for (char c : apiKey) {
            if (!std::isalnum(static_cast<unsigned char>(c)) && c != '-' && c != '_') {
                return false;
            }
        }
    }
    return true;
}

std::string GeminiApiClient::buildEndpointUrl(const std::string& model, const std::string& apiKey) {
    std::string activeModel = model.empty() ? "gemini-3.8-flash" : model;
    std::string url;
    url.reserve(80 + activeModel.size() + apiKey.size());
    url.append("https://generativelanguage.googleapis.com/v1beta/models/")
       .append(activeModel)
       .append(":generateContent?key=")
       .append(apiKey);
    return url;
}

std::string GeminiApiClient::escapeJsonString(const std::string& input) {
    std::string out;
    out.reserve(input.size() + (input.size() >> 3) + 8);
    for (char c : input) {
        switch (c) {
            case '"':  out.append("\\\""); break;
            case '\\': out.append("\\\\"); break;
            case '\b': out.append("\\b"); break;
            case '\f': out.append("\\f"); break;
            case '\n': out.append("\\n"); break;
            case '\r': out.append("\\r"); break;
            case '\t': out.append("\\t"); break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned char>(c));
                    out.append(buf);
                } else {
                    out.push_back(c);
                }
                break;
        }
    }
    return out;
}

std::string GeminiApiClient::buildRequestPayload(const std::string& prompt, const std::string& thinkingLevel) {
    std::string level = thinkingLevel.empty() ? "high" : thinkingLevel;
    std::string escapedPrompt = escapeJsonString(prompt);
    std::string escapedLevel = escapeJsonString(level);

    std::string out;
    out.reserve(escapedPrompt.size() + 220);
    out.append("{\"contents\":[{\"parts\":[{\"text\":\"")
       .append(escapedPrompt)
       .append("\"}]}],\"generationConfig\":{\"temperature\":0.7,\"topP\":0.95,\"maxOutputTokens\":4096,\"thinkingConfig\":{\"thinkingLevel\":\"")
       .append(escapedLevel)
       .append("\"}}}");
    return out;
}

std::string GeminiApiClient::buildRequestPayloadWithFile(const std::string& prompt, const std::string& mimeType, const std::string& base64Data, const std::string& thinkingLevel) {
    std::string level = thinkingLevel.empty() ? "high" : thinkingLevel;
    std::string textPrompt;
    if (prompt.empty()) {
        textPrompt = "Analiza y describe detalladamente esta imagen o archivo adjunto.";
    } else {
        textPrompt = "El usuario ha adjuntado esta imagen o captura de pantalla junto con el siguiente mensaje: \"" + prompt + "\"\n\n"
                     "Instrucción importante: Analiza detenidamente el contenido visual de la imagen adjunta y responde al mensaje del usuario basándote directamente en lo que se ve en ella. "
                     "Nunca ignores la imagen adjunta; incluso si el mensaje del usuario es un saludo breve o general, responde a su mensaje describiendo o comentando lo que aparece en la imagen.";
    }
    std::string escapedMime = escapeJsonString(mimeType);
    std::string escapedPrompt = escapeJsonString(textPrompt);
    std::string escapedLevel = escapeJsonString(level);

    std::string out;
    out.reserve(base64Data.size() + escapedPrompt.size() + 320);
    out.append("{\"contents\":[{\"parts\":[{\"inlineData\":{\"mimeType\":\"")
       .append(escapedMime)
       .append("\",\"data\":\"")
       .append(base64Data)
       .append("\"}},{\"text\":\"")
       .append(escapedPrompt)
       .append("\"}]}],\"generationConfig\":{\"temperature\":0.7,\"topP\":0.95,\"maxOutputTokens\":4096,\"thinkingConfig\":{\"thinkingLevel\":\"")
       .append(escapedLevel)
       .append("\"}}}");
    return out;
}

static std::string decodeJsonStringAt(const std::string& jsonString, size_t start, size_t* outEndPos) {
    std::string result;
    bool escaped = false;
    size_t i = start;
    for (; i < jsonString.length(); ++i) {
        char c = jsonString[i];
        if (escaped) {
            switch (c) {
                case 'n': result += '\n'; break;
                case 'r': result += '\r'; break;
                case 't': result += '\t'; break;
                case '"': result += '"'; break;
                case '\\': result += '\\'; break;
                case 'u': {
                    if (i + 4 < jsonString.length()) {
                        std::string hexStr = jsonString.substr(i + 1, 4);
                        char* endPtr = nullptr;
                        long codePoint = strtol(hexStr.c_str(), &endPtr, 16);
                        if (endPtr == hexStr.c_str() + 4) {
                            i += 4;
                            if (codePoint < 0x80) {
                                result += static_cast<char>(codePoint);
                            } else if (codePoint < 0x800) {
                                result += static_cast<char>(0xC0 | (codePoint >> 6));
                                result += static_cast<char>(0x80 | (codePoint & 0x3F));
                            } else {
                                result += static_cast<char>(0xE0 | (codePoint >> 12));
                                result += static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F));
                                result += static_cast<char>(0x80 | (codePoint & 0x3F));
                            }
                            break;
                        }
                    }
                    result += 'u';
                    break;
                }
                default: result += c; break;
            }
            escaped = false;
        } else if (c == '\\') {
            escaped = true;
        } else if (c == '"') {
            ++i;
            break;
        } else {
            result += c;
        }
    }
    if (outEndPos) *outEndPos = i;
    return result;
}

std::string GeminiApiClient::parseTextResponse(const std::string& jsonString) {
    std::string finalResult;
    std::string fallbackResult;
    size_t searchPos = 0;

    while (searchPos < jsonString.length()) {
        size_t keyPos = jsonString.find("\"text\"", searchPos);
        if (keyPos == std::string::npos) break;

        size_t colonPos = jsonString.find(':', keyPos + 6);
        if (colonPos == std::string::npos) break;

        size_t quoteStart = colonPos + 1;
        while (quoteStart < jsonString.length() &&
               (jsonString[quoteStart] == ' ' || jsonString[quoteStart] == '\t' ||
                jsonString[quoteStart] == '\n' || jsonString[quoteStart] == '\r')) {
            ++quoteStart;
        }
        if (quoteStart >= jsonString.length() || jsonString[quoteStart] != '"') {
            searchPos = colonPos + 1;
            continue;
        }

        size_t quoteEnd = quoteStart + 1;
        std::string partText = decodeJsonStringAt(jsonString, quoteStart + 1, &quoteEnd);
        searchPos = quoteEnd;

        // Check if this part object is an internal model thought ("thought": true)
        size_t braceBefore = jsonString.rfind('{', keyPos);
        size_t braceAfter = jsonString.find('}', quoteEnd);
        bool isThought = false;
        if (braceBefore != std::string::npos && keyPos > braceBefore) {
            std::string beforeSlice = jsonString.substr(braceBefore, keyPos - braceBefore);
            if (beforeSlice.find("\"thought\"") != std::string::npos &&
                beforeSlice.find("true") != std::string::npos) {
                isThought = true;
            }
        }
        if (!isThought && braceAfter != std::string::npos && braceAfter > quoteEnd) {
            std::string afterSlice = jsonString.substr(quoteEnd, braceAfter - quoteEnd);
            if (afterSlice.find("\"thought\"") != std::string::npos &&
                afterSlice.find("true") != std::string::npos) {
                isThought = true;
            }
        }

        fallbackResult = partText;
        if (!isThought) {
            finalResult += partText;
        }
    }

    if (!finalResult.empty()) {
        return finalResult;
    }
    if (!fallbackResult.empty()) {
        return fallbackResult;
    }

    size_t errPos = jsonString.find("\"message\": \"");
    if (errPos != std::string::npos) {
        size_t start = errPos + 12;
        size_t end = jsonString.find("\"", start);
        if (end != std::string::npos) {
            return "Error de Gemini: " + jsonString.substr(start, end - start);
        }
    }
    return "No se pudo extraer texto de la respuesta.";
}

