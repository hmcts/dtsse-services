package uk.gov.hmcts.reform.services.listassist.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractNameTest {

    @Test
    void parsesObservedLayoutWithExactViewToken() {
        assertThat(ExtractName.parse("2040-01/2040-01-30-dbo-Full_vhmcts_Hearings-20400130030000-data.parquet",
            "vhmcts_Hearings")).contains(new ExtractName(
                "2040-01/2040-01-30-dbo-Full_vhmcts_Hearings-20400130030000-data.parquet", "Full", "20400130030000"));
    }

    @Test
    void rejectsOtherTokensCasingAndShapes() {
        assertThat(Stream.of(
            "2040-01/2040-01-30-dbo-Full_vhmcts_hearings-20400130030000-data.parquet",
            "2040-01/2040-01-30-dbo-Full_vhmcts_Hearings_JOfficer-20400130030000-data.parquet",
            "2040-01-30-dbo-Full_vhmcts_Hearings-20400130030000-data.parquet",
            "2040-01/2040-01-30-dbo-Full_vhmcts_Hearings-20400130030000-schema.parquet",
            "2040-01/2040-01-30-dbo-Delta_vhmcts_Hearings-20400130030000-data.parquet",
            "_metadata.json"))
            .allSatisfy(name -> assertThat(ExtractName.parse(name, "vhmcts_Hearings")).isEmpty());
    }

    @Test
    void rejectsImpossibleFolderDateAndTimestamp() {
        assertThat(Stream.of(
            "2040-01/2040-01-30-dbo-Full_vhmcts_Hearings-20409999999999-data.parquet",
            "2040-01/2040-01-30-dbo-Full_vhmcts_Hearings-20400230030000-data.parquet",
            "2040-01/2040-01-30-dbo-Full_vhmcts_Hearings-20400130240000-data.parquet",
            "2040-01/2040-01-30-dbo-Full_vhmcts_Hearings-20400130036000-data.parquet",
            "2040-01/2040-02-30-dbo-Full_vhmcts_Hearings-20400130030000-data.parquet",
            "2040-13/2040-01-30-dbo-Full_vhmcts_Hearings-20400130030000-data.parquet"))
            .allSatisfy(name -> assertThat(ExtractName.parse(name, "vhmcts_Hearings")).isEmpty());
        assertThat(ExtractName.parse("2040-02/2040-02-29-dbo-Incr_vhmcts_Hearings-20400229235959-data.parquet",
            "vhmcts_Hearings")).isPresent();
    }

    @Test
    void ordersByFilenameTimestampNotPath() {
        ExtractName later = ExtractName.parse("2040-01/2040-01-30-dbo-Incr_vhmcts_user-20400130110000-data.parquet",
            "vhmcts_user").orElseThrow();
        ExtractName earlier = ExtractName.parse("2040-02/2040-01-30-dbo-Full_vhmcts_user-20400130030000-data.parquet",
            "vhmcts_user").orElseThrow();
        assertThat(Stream.of(later, earlier).sorted(ExtractName.DELIVERY_ORDER).toList())
            .isEqualTo(List.of(earlier, later));
    }
}
