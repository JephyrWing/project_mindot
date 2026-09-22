package com.my.mindot_back.records.dto;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class EmotionRecordsUpdateRequestDtoTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test void fullEditDeserializesAndValidatesNestedAnalysis() {
        String json = """
                {"rawText":"  수정 원문  ","analysis":{"situationText":"상황",
                  "automaticThought":"  ","primaryEmotionCode":"  짜증  ","primaryIntensity":3,
                  "secondaryEmotions":[],"contextCategory":"WORK","details":{"behavior":"산책"}}}
                """;
        var dto = mapper.readValue(json, EmotionRecordsUpdateRequestDto.class);
        assertEquals("수정 원문", dto.rawText());
        assertNull(dto.analysis().automaticThought());
        assertEquals("짜증", dto.analysis().primaryEmotionCode());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(dto).isEmpty());
            for (String invalid : new String[]{json.replace("짜증", "가".repeat(51)),
                    json.replace("\"primaryIntensity\":3", "\"primaryIntensity\":11"),
                    json.replace("수정 원문", " ")}) {
                assertFalse(factory.getValidator().validate(
                        mapper.readValue(invalid, EmotionRecordsUpdateRequestDto.class)).isEmpty());
            }
        }
    }

    @Test void jsonCanOmitEitherFieldAndNormalizeThought() {
        var thought = mapper.readValue("{\"automaticThought\":\"  생각  \"}", EmotionRecordsUpdateRequestDto.class);
        assertNull(thought.occurredAt());
        assertEquals("생각", thought.automaticThought());
        var time = mapper.readValue("{\"occurredAt\":\"2026-09-15T00:00:00Z\"}", EmotionRecordsUpdateRequestDto.class);
        assertNotNull(time.occurredAt());
        assertNull(time.automaticThought());
        var both = mapper.readValue("{\"occurredAt\":\"2026-09-15T00:00:00Z\",\"automaticThought\":\"생각\"}", EmotionRecordsUpdateRequestDto.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (var dto : new EmotionRecordsUpdateRequestDto[]{thought, time, both})
                assertTrue(factory.getValidator().validate(dto).isEmpty());
        }
    }

    @Test void explicitNullCannotClearEitherField() {
        for (String json : new String[]{"{\"occurredAt\":null}", "{\"automaticThought\":null}",
                "{\"occurredAt\":\"2026-09-15T00:00:00Z\",\"automaticThought\":null}"}) {
            assertThrows(tools.jackson.core.JacksonException.class,
                    () -> mapper.readValue(json, EmotionRecordsUpdateRequestDto.class));
        }
    }

    @Test void emptyObjectAndBlankThoughtFailValidation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (String json : new String[]{"{}", "{\"automaticThought\":\"   \"}"}) {
                var dto = mapper.readValue(json, EmotionRecordsUpdateRequestDto.class);
                assertFalse(factory.getValidator().validate(dto).isEmpty());
            }
        }
    }
}
