package com.aiform.id995a.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TemplateClassificationLogService {

  private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final Path workspaceRoot;
  private final ObjectMapper objectMapper;

  @Autowired
  public TemplateClassificationLogService(ObjectMapper objectMapper) {
    this(resolveWorkspaceRoot(), objectMapper);
  }

  TemplateClassificationLogService(Path workspaceRoot) {
    this(workspaceRoot, new ObjectMapper());
  }

  TemplateClassificationLogService(Path workspaceRoot, ObjectMapper objectMapper) {
    this.workspaceRoot = workspaceRoot;
    this.objectMapper = objectMapper;
  }

  public synchronized void record(String filename, DocumentTemplate template) {
    if (template == null) {
      return;
    }
    String safeFilename = filename == null || filename.isBlank() ? "uploaded-document" : filename.trim();
    try {
      Map<String, TemplateClassificationEntry> entries = readEntries();
      TemplateClassificationEntry previous = entries.get(safeFilename);
      entries.put(
          safeFilename,
          new TemplateClassificationEntry(
              safeFilename,
              template.templateId(),
              template.footerId(),
              template.pageCount(),
              template.matchSource(),
              Math.round(template.confidence()),
              template.structureHash(),
              previous == null ? now() : previous.firstSeenAt(),
              now()
          )
      );
      writeJson(entries);
      writeMarkdown(entries);
    } catch (IOException | RuntimeException exception) {
      // Classification logging is useful for review, but OCR results should not fail if disk logging fails.
    }
  }

  private Map<String, TemplateClassificationEntry> readEntries() throws IOException {
    Path path = jsonPath();
    Map<String, TemplateClassificationEntry> entries = new LinkedHashMap<>();
    if (!Files.exists(path)) {
      return entries;
    }
    JsonNode root = objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
    JsonNode array = root.path("entries");
    if (!array.isArray()) {
      return entries;
    }
    for (JsonNode item : array) {
      TemplateClassificationEntry entry = objectMapper.treeToValue(item, TemplateClassificationEntry.class);
      if (entry != null && !entry.filename().isBlank()) {
        entries.put(entry.filename(), entry);
      }
    }
    return entries;
  }

  private void writeJson(Map<String, TemplateClassificationEntry> entries) throws IOException {
    Files.createDirectories(jsonPath().getParent());
    com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
    ArrayNode array = root.putArray("entries");
    for (TemplateClassificationEntry entry : entries.values()) {
      array.add(objectMapper.valueToTree(entry));
    }
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath().toFile(), root);
  }

  private void writeMarkdown(Map<String, TemplateClassificationEntry> entries) throws IOException {
    Files.createDirectories(markdownPath().getParent());
    StringBuilder builder = new StringBuilder();
    builder.append("# OCR Template Classification Log\n\n");
    builder.append("| Filename | Template ID | Footer ID | Pages | Match Source | Confidence | First Seen | Last Seen |\n");
    builder.append("|---|---|---|---:|---|---:|---|---|\n");
    for (TemplateClassificationEntry entry : entries.values()) {
      builder.append("| ")
          .append(escape(entry.filename()))
          .append(" | ")
          .append(escape(entry.templateId()))
          .append(" | ")
          .append(escape(entry.footerId()))
          .append(" | ")
          .append(entry.pageCount())
          .append(" | ")
          .append(escape(entry.matchSource()))
          .append(" | ")
          .append(entry.confidence())
          .append(" | ")
          .append(escape(entry.firstSeenAt()))
          .append(" | ")
          .append(escape(entry.lastSeenAt()))
          .append(" |\n");
    }
    Files.writeString(markdownPath(), builder.toString(), StandardCharsets.UTF_8);
  }

  private String escape(String value) {
    return (value == null ? "" : value).replace("|", "\\|");
  }

  private String now() {
    return LocalDateTime.now().format(TIMESTAMP_FORMAT);
  }

  private Path markdownPath() {
    return workspaceRoot.resolve("docs").resolve("template-classification.md");
  }

  private Path jsonPath() {
    return workspaceRoot.resolve("data").resolve("template-classification.json");
  }

  private static Path resolveWorkspaceRoot() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    if ("backend".equalsIgnoreCase(cwd.getFileName() == null ? "" : cwd.getFileName().toString())) {
      return cwd.getParent();
    }
    return cwd;
  }

  public record TemplateClassificationEntry(
      String filename,
      String templateId,
      String footerId,
      int pageCount,
      String matchSource,
      long confidence,
      String structureHash,
      String firstSeenAt,
      String lastSeenAt
  ) {
    public TemplateClassificationEntry {
      filename = filename == null ? "" : filename;
      templateId = templateId == null ? "" : templateId;
      footerId = footerId == null ? "" : footerId;
      matchSource = matchSource == null ? "" : matchSource;
      structureHash = structureHash == null ? "" : structureHash;
      firstSeenAt = firstSeenAt == null ? "" : firstSeenAt;
      lastSeenAt = lastSeenAt == null ? "" : lastSeenAt;
    }
  }
}
