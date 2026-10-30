#pragma once

#include <string>

class GeminiApiClient {
public:
    static bool validateApiKeyFormat(const std::string& apiKey);
    static std::string buildEndpointUrl(const std::string& model, const std::string& apiKey);
    static std::string buildRequestPayload(const std::string& prompt, const std::string& thinkingLevel = "high");
    static std::string buildRequestPayloadWithFile(const std::string& prompt, const std::string& mimeType, const std::string& base64Data, const std::string& thinkingLevel = "high");
    static std::string parseTextResponse(const std::string& jsonString);

private:
    static std::string escapeJsonString(const std::string& input);
};
