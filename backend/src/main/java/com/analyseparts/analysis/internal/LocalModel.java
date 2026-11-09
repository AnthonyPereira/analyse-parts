package com.analyseparts.analysis.internal;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Une question au modèle local, avec une réponse au format JSON imposé (JSON Schema) : Ollama
 * refuse toute autre forme. Partagé par la lecture des rapports annuels et des rapports semestriels.
 */
final class LocalModel {

    private LocalModel() {
    }

    /**
     * @param maxTokens longueur maximale de la réponse : avec un JSON imposé, le modèle peut tourner en
     *                  boucle (des espaces sans fin) ; une réponse coupée n'est plus du JSON valide
     * @throws tools.jackson.core.JacksonException si la réponse n'est pas lisible
     */
    static <T> T ask(ChatModel chat, JsonMapper json, String model, int contextTokens, int maxTokens,
                     String system, String userPrompt, Map<String, Object> schema, Class<T> type) {
        // Spring AI 2.0 : les options passées à l'appel remplacent celles de la configuration, modèle compris
        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(model)
                .format(schema)
                .numPredict(maxTokens)
                .disableThinking() // qwen3 « réfléchit » par défaut : plus long, pas plus fidèle aux extraits
                .numCtx(contextTokens)
                .temperature(0.1) // peu de hasard : on veut recopier, pas créer
                .build();
        String content = chat.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(userPrompt)), options))
                .getResult().getOutput().getText();
        return json.readValue(content, type);
    }
}
