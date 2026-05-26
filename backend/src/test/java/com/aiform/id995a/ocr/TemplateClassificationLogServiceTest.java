package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateClassificationLogServiceTest {

  @TempDir
  private Path root;

  @Test
  void writesMarkdownAndJsonClassificationRecord() throws Exception {
    TemplateClassificationLogService service = new TemplateClassificationLogService(root);

    service.record(
        "黄晓兰A.pdf",
        new DocumentTemplate("id988a_2024_06", "ID 988A (06/2024)", 5, 98, "footer_ocr", "abc123")
    );

    String markdown = Files.readString(root.resolve("docs").resolve("template-classification.md"));
    assertThat(markdown).contains("| 黄晓兰A.pdf | id988a_2024_06 | ID 988A (06/2024) | 5 | footer_ocr | 98 |");

    String json = Files.readString(root.resolve("data").resolve("template-classification.json"));
    assertThat(json).contains("\"filename\" : \"黄晓兰A.pdf\"");
    assertThat(json).contains("\"templateId\" : \"id988a_2024_06\"");
  }

  @Test
  void updatesExistingFilenameInsteadOfDuplicatingRows() throws Exception {
    TemplateClassificationLogService service = new TemplateClassificationLogService(root);

    service.record(
        "upload.pdf",
        new DocumentTemplate("unknown_1p_11111111", "", 1, 65, "structure_hash", "11111111")
    );
    service.record(
        "upload.pdf",
        new DocumentTemplate("id407_2016_11", "ID 407 (11/2016)", 4, 98, "footer_ocr", "22222222")
    );

    String markdown = Files.readString(root.resolve("docs").resolve("template-classification.md"));
    assertThat(markdown).contains("| upload.pdf | id407_2016_11 | ID 407 (11/2016) | 4 | footer_ocr | 98 |");
    assertThat(markdown).doesNotContain("unknown_1p_11111111");
  }
}
