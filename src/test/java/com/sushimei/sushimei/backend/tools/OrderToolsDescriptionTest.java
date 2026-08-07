package com.sushimei.sushimei.backend.tools;

import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderToolsDescriptionTest {

    @Test
    void cartToolDescriptionsReserveEachToolForTheRequestedOperation() throws NoSuchMethodException {
        String addDescription = toolDescription("addDishToCart", String.class, String.class, int.class, double.class);
        String checkDescription = toolDescription("checkCart", String.class);
        String removeDescription = toolDescription("removeDishFromCart", String.class, String.class, int.class);

        assertThat(addDescription).contains("solo cuando", "No la uses para saludos", "varios productos");
        assertThat(checkDescription).contains("solo cuando", "qué lleva", "No la uses para saludos", "preguntas del menú");
        assertThat(removeDescription).contains("solo cuando", "quitar", "eliminar");
    }

    private String toolDescription(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        return String.join(" ", OrderTools.class.getMethod(methodName, parameterTypes).getAnnotation(Tool.class).value());
    }
}
