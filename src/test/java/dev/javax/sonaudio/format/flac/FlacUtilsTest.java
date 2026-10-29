package dev.javax.sonaudio.format.flac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import dev.javax.sonaudio.metadata.Blob;
import dev.javax.sonaudio.metadata.TagValue;

class FlacUtilsTest {

    @Test
    void toFieldsWritesOneUpperCaseFieldPerValue() {
        final var values = new LinkedHashSet<TagValue>(List.of(new TagValue.Str("Coldplay"), new TagValue.Int(9)));

        final var fields = FlacUtils.toFields("artist", values).toList();

        assertThat(fields).containsExactly(new Field("ARTIST", "Coldplay"), new Field("ARTIST", "9"));
    }

    @Test
    void toFieldsRefusesBinaryValues() {
        final var values = Set.<TagValue>of(new TagValue.Binary(Blob.copyOf(new byte[] {1})));

        assertThatThrownBy(() -> FlacUtils.toFields("picture", values).toList())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
