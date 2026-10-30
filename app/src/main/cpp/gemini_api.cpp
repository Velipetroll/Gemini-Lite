#include "gemini_api.h"
#include <sstream>
#include <regex>

bool GeminiApiClient::validateApiKeyFormat(const std::string& apiKey) {
    if (apiKey.length() < 30) {
        return false;
    }
    if (apiKey.rfind("AIzaSy", 0) != 0) {
        for (char c : apiKey) {
            if (!std::isalnum(c) && c != '-' && c != '_') {
                return false;
            }
        }
    }
    return true;
}

std::string GeminiApiClient::buildEndpointUrl(const std::string& model, const std::string& apiKey) {
    std::string activeModel = model.empty() ? "gemini-3.8-flash" : model;
    return "https://generativelanguage.googleapis.com/v1beta/models/" + activeModel + ":generateContent?key=" + apiKey;
}

std::string GeminiApiClient::escapeJsonString(const std::string& input) {
    std::ostringstream ss;
    for (char c : input) {
        switch (c) {
            case '"':  ss << "\\\""; break;
            case '\\': ss << "\\\\"; break;
            case '\b': ss << "\\b"; break;
            case '\f': ss << "\\f"; break;
            case '\n': ss << "\\n"; break;
            case '\r': ss << "\\r"; break;
            case '\t': ss << "\\t"; break;
            default:
                if ('\x00' <= c && c <= '\x1f') {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", (unsigned char)c);
                    ss << buf;
                } else {
                    ss << c;
                }
                break;
        }
    }
    return ss.str();
}

std::string GeminiApiClient::buildRequestPayload(const std::string& prompt, const std::string& thinkingLevel) {
    std::string level = thinkingLevel.empty() ? "high" : thinkingLevel;
    
    std::ostringstream ss;
    ss << "{"
       << "\"contents\": ["
       << "  {"
       << "    \"parts\": ["
       << "      { \"text\": \"" << escapeJsonString(prompt) << "\" }"
       << "    ]"
       << "  }"
       << "],"
       << "\"generationConfig\": {"
       << "  \"temperature\": 0.7,"
       << "  \"topP\": 0.95,"
       << "  \"maxOutputTokens\": 4096,"
       << "  \"thinkingConfig\": {"
       << "    \"thinkingLevel\": \"" << escapeJsonString(level) << "\""
       << "  }"
       << "}"
       << "}";
    return ss.str();
}

std::string GeminiApiClient::buildRequestPayloadWithFile(const std::string& prompt, const std::string& mimeType, const std::string& base64Data, const std::string& thinkingLevel) {
    std::string level = thinkingLevel.empty() ? "high" : thinkingLevel;
    std::string textPrompt = prompt.empty() ? "Describe o analiza este archivo." : prompt;
    
    std::ostringstream ss;
    ss << "{"
       << "\"contents\": ["
       << "  {"
       << "    \"parts\": ["
       << "      {"
       << "        \"inlineData\": {"
       << "          \"mimeType\": \"" << escapeJsonString(mimeType) << "\","
       << "          \"data\": \"" << base64Data << "\""
       << "        }"
       << "      },"
       << "      { \"text\": \"" << escapeJsonString(textPrompt) << "\" }"
       << "    ]"
       << "  }"
       << "],"
       << "\"generationConfig\": {"
       << "  \"temperature\": 0.7,"
       << "  \"topP\": 0.95,"
       << "  \"maxOutputTokens\": 4096,"
       << "  \"thinkingConfig\": {"
       << "    \"thinkingLevel\": \"" << escapeJsonString(level) << "\""
       << "  }"
       << "}"
       << "}";
    return ss.str();
}

std::string GeminiApiClient::parseTextResponse(const std::string& jsonString) {
    std::string key = "\"text\": \"";
    size_t pos = jsonString.find(key);
    if (pos == std::string::npos) {
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

    size_t start = pos + key.length();
    std::string result;
    bool escaped = false;
    for (size_t i = start; i < jsonString.length(); ++i) {
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
            break;
        } else {
            result += c;
        }
    }
    return result;
}
