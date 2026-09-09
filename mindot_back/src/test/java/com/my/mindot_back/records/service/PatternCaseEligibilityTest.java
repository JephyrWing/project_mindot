package com.my.mindot_back.records.service;
import com.my.mindot_back.records.dto.PatternSimilarCaseDto;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PatternCaseEligibilityTest {
    private PatternSimilarCaseDto sample(String version,List<String> codes,Map<String,Object> result) {
        return new PatternSimilarCaseDto(1L,"상황","처음 생각","수정 생각",(short)4,codes,version,result);
    }
    @Test void confirmedAfterWithAllTypesRejectedIsStillACase() {
        assertTrue(sample("cbt-insight-1",List.of(),Map.of("userConfirmed",true,"afterText","수정 생각")).eligibleForPattern());
    }
    @Test void unconfirmedOrBlankAfterIsNotACase() {
        assertFalse(sample("cbt-insight-1",List.of("LABELING"),Map.of("userConfirmed",false,"afterText","초안")).eligibleForPattern());
        assertFalse(sample("cbt-insight-1",List.of(),Map.of("userConfirmed",true,"afterText"," ")).eligibleForPattern());
    }
    @Test void legacyEligibilityIsUnchanged() {
        assertFalse(sample("legacy",List.of(),null).eligibleForPattern());
        assertTrue(sample("legacy",List.of("LABELING"),null).eligibleForPattern());
    }
}
