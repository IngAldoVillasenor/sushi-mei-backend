package com.sushimei.sushimei.backend.development;

import com.sushimei.sushimei.backend.conversation.ConversationManager;
import com.sushimei.sushimei.backend.service.WhatsAppService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalAiConversationHarnessTest {

    @Mock
    private ConversationManager conversationManager;

    @Mock
    private LocalAiConversationHarnessService harnessService;

    @Test
    void serviceUsesTheProductionTextPathWithoutAnyWhatsAppDependency() {
        when(conversationManager.handleTextMessage("memory-1", "525512345678", "Hola")).thenReturn("¡Hola!");
        LocalAiConversationHarnessService service = new LocalAiConversationHarnessService(conversationManager);

        String response = service.chat("memory-1", "525512345678", "Hola");

        assertThat(response).isEqualTo("¡Hola!");
        verify(conversationManager).handleTextMessage("memory-1", "525512345678", "Hola");
        assertThat(LocalAiConversationHarnessService.class.getDeclaredConstructors()[0].getParameterTypes())
                .containsExactly(ConversationManager.class)
                .doesNotContain(WhatsAppService.class);
    }

    @Test
    void controllerForwardsOnlyToTheLocalHarnessAndReturnsItsResponse() {
        when(harnessService.chat("memory-1", "525512345678", "Hola")).thenReturn("respuesta");
        LocalAiConversationHarnessController controller = new LocalAiConversationHarnessController(harnessService);

        LocalAiChatResponse response = controller.chat(new LocalAiChatRequest("memory-1", "525512345678", "Hola"));

        assertThat(response).isEqualTo(new LocalAiChatResponse("respuesta"));
        verify(harnessService).chat("memory-1", "525512345678", "Hola");
        assertThat(LocalAiConversationHarnessController.class.getDeclaredConstructors()[0].getParameterTypes())
                .containsExactly(LocalAiConversationHarnessService.class)
                .doesNotContain(WhatsAppService.class);
    }

    @Test
    void serviceRejectsBlankInputsBeforeCallingTheConversationPath() {
        LocalAiConversationHarnessService service = new LocalAiConversationHarnessService(conversationManager);

        assertThatThrownBy(() -> service.chat(" ", "525512345678", "Hola"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(conversationManager);
    }

    @Test
    void harnessIsOnlyAvailableWhenLocalProfileAndExplicitPropertyAreEnabled() throws NoSuchMethodException {
        assertThat(LocalAiConversationHarnessController.class.getAnnotation(Profile.class).value()).containsExactly("local");
        assertThat(LocalAiConversationHarnessService.class.getAnnotation(Profile.class).value()).containsExactly("local");

        ConditionalOnProperty controllerCondition = LocalAiConversationHarnessController.class
                .getAnnotation(ConditionalOnProperty.class);
        ConditionalOnProperty serviceCondition = LocalAiConversationHarnessService.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(controllerCondition.prefix()).isEqualTo("development.ai-harness");
        assertThat(controllerCondition.name()).containsExactly("enabled");
        assertThat(controllerCondition.havingValue()).isEqualTo("true");
        assertThat(controllerCondition.matchIfMissing()).isFalse();
        assertThat(serviceCondition).isNotNull();
        assertThat(Arrays.asList(LocalAiConversationHarnessController.class.getDeclaredConstructors()[0].getParameterTypes()))
                .doesNotContain(WhatsAppService.class);
    }

    @Test
    void declaresUtf8CompatibleJsonRequestAndResponseMediaTypes() throws NoSuchMethodException {
        PostMapping mapping = LocalAiConversationHarnessController.class
                .getDeclaredMethod("chat", LocalAiChatRequest.class)
                .getAnnotation(PostMapping.class);

        assertThat(mapping.consumes()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
        assertThat(mapping.produces()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
    }
}
