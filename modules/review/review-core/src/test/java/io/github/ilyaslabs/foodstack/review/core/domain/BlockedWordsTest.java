package io.github.ilyaslabs.foodstack.review.core.domain;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlockedWordsTest {

    @Test
    @DisplayName("masks whole blocked words in any case, stretched or not, and leaves clean text alone")
    void masks() {
        BlockedWords words = BlockedWords.of(List.of("shit", "bad word"));

        assertThat(words.mask("Shit food, SHIIIT service")).isEqualTo(new BlockedWords.Result("**** food, ****** service", true));
        assertThat(words.mask("a bad   word here")).isEqualTo(new BlockedWords.Result("a ********** here", true));
        assertThat(words.mask("Shitake mushrooms were great")).isEqualTo(new BlockedWords.Result("Shitake mushrooms were great", false));
        assertThat(words.mask(null)).isEqualTo(new BlockedWords.Result(null, false));
    }

    @Test
    @DisplayName("the built-in list covers English, Arabic and Urdu, and the operator adds words")
    void builtIn() {
        BlockedWords words = BlockedWords.withBuiltIn(List.of(" Rubbish "));

        assertThat(words.mask("what a fucking mess").flagged()).isTrue();
        assertThat(words.mask("الطعام زبالة").text()).isEqualTo("الطعام *****");
        assertThat(words.mask("ڈرائیور کمینہ تھا").flagged()).isTrue();
        assertThat(words.mask("rubbish pizza").text()).isEqualTo("******* pizza");
        assertThat(words.mask("Lovely biryani, will order again").flagged()).isFalse();
    }
}
