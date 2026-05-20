package com.aiform.id995a.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

@Service
public class StructuredFieldEvidenceService {

  public StructuredFieldEvidenceService() {}

  public Map<Integer, List<StructuredFieldDetail>> buildFieldDetails(
      JsonNode structuredData,
      List<RenderedOcrPage> pages
  ) {
    Map<Integer, List<StructuredFieldDetail>> detailsByPage = new LinkedHashMap<>();
    for (RenderedOcrPage page : pages) {
      String pageKey = "page_" + page.page();
      JsonNode pageData = structuredData == null ? NullNode.getInstance() : structuredData.path(pageKey);
      JsonNode confidenceData = metadataPage(structuredData, "_confidence", pageKey);
      JsonNode evidenceData = metadataPage(structuredData, "_field_evidence", pageKey);
      List<FieldCandidate> candidates = new ArrayList<>();
      collectCandidates(pageData, List.of(), candidates);
      List<PreparedField> preparedFields = candidates.stream()
          .map(candidate -> prepareField(page, candidate, confidenceData, evidenceData))
          .toList();
      List<StructuredFieldDetail> details = new ArrayList<>();
      for (PreparedField preparedField : preparedFields) {
        details.add(toDetail(preparedField));
      }
      detailsByPage.put(page.page(), details);
    }
    return detailsByPage;
  }

  private PreparedField prepareField(
      RenderedOcrPage page,
      FieldCandidate candidate,
      JsonNode confidenceData,
      JsonNode evidenceData
  ) {
    String pageKey = "page_" + page.page();
    JsonNode evidence = lookupMetadata(evidenceData, candidate.path(), pageKey);
    double confidence = confidence(evidence, confidenceData, candidate.path(), candidate.value(), pageKey);
    List<Integer> bbox = parseBbox(evidence, page.imageWidth(), page.imageHeight());
    CropResult crop = crop(page, bbox);
    String valueText = valueText(candidate.value());
    List<FieldCharacterEvidence> characters = characters(evidence, valueText, bbox);
    return new PreparedField(
        page.page(),
        candidate.path(),
        label(evidence, candidate.path()),
        candidate.value(),
        displayValue(candidate.path(), label(evidence, candidate.path()), candidate.value()),
        confidence,
        bbox,
        crop,
        valueText,
        characters
    );
  }

  private StructuredFieldDetail toDetail(PreparedField prepared) {
    String fieldPath = String.join(".", prepared.path());
    return new StructuredFieldDetail(
        prepared.page(),
        fieldPath,
        prepared.label(),
        prepared.value(),
        prepared.displayValue(),
        prepared.confidence(),
        prepared.bbox(),
        prepared.crop().dataUrl(),
        "",
        0,
        "not_run",
        prepared.characters()
    );
  }

  private void collectCandidates(JsonNode node, List<String> path, List<FieldCandidate> candidates) {
    if (node == null || node.isMissingNode()) {
      return;
    }
    if (isLeafValue(node)) {
      candidates.add(new FieldCandidate(path, node));
      return;
    }
    if (isMetadataLeaf(node)) {
      JsonNode value = node.has("value") ? node.path("value") : node.path("text");
      candidates.add(new FieldCandidate(path, value));
      return;
    }
    if (node.isArray()) {
      for (int index = 0; index < node.size(); index += 1) {
        collectCandidates(node.get(index), append(path, String.valueOf(index + 1)), candidates);
      }
      return;
    }
    if (node.isObject()) {
      node.fields().forEachRemaining(entry -> {
        if (!entry.getKey().startsWith("_")) {
          collectCandidates(entry.getValue(), append(path, entry.getKey()), candidates);
        }
      });
    }
  }

  private boolean isLeafValue(JsonNode node) {
    return node.isNull() || node.isTextual() || node.isBoolean() || node.isNumber();
  }

  private boolean isMetadataLeaf(JsonNode node) {
    return node != null
        && node.isObject()
        && (node.has("value") || node.has("text"))
        && (node.has("confidence") || node.size() <= 4);
  }

  private JsonNode metadataPage(JsonNode structuredData, String key, String pageKey) {
    if (structuredData == null || !structuredData.has(key)) {
      return NullNode.getInstance();
    }
    JsonNode metadata = structuredData.path(key);
    return metadata.path(pageKey).isMissingNode() ? NullNode.getInstance() : metadata.path(pageKey);
  }

  private JsonNode lookupMetadata(JsonNode root, List<String> path) {
    return lookupMetadata(root, path, "");
  }

  private JsonNode lookupMetadata(JsonNode root, List<String> path, String pageKey) {
    if (root == null || root.isMissingNode() || root.isNull()) {
      return NullNode.getInstance();
    }
    String dottedPath = String.join(".", path);
    if (!pageKey.isBlank()) {
      JsonNode pagePrefixed = root.path(pageKey + "." + dottedPath);
      if (!pagePrefixed.isMissingNode()) {
        return pagePrefixed;
      }
    }
    JsonNode direct = root.path(dottedPath);
    if (!direct.isMissingNode()) {
      return direct;
    }
    JsonNode current = root;
    for (String part : path) {
      current = current.path(part);
      if (current.isMissingNode()) {
        return NullNode.getInstance();
      }
    }
    return current;
  }

  private double confidence(JsonNode evidence, JsonNode confidenceData, List<String> path, JsonNode value, String pageKey) {
    JsonNode explicit = firstExisting(evidence, "confidence", "value_confidence", "field_confidence");
    if (explicit.isNumber()) {
      return normalizeConfidence(explicit.asDouble());
    }
    if (confidenceData != null && confidenceData.isNumber()) {
      return normalizeConfidence(confidenceData.asDouble());
    }
    JsonNode fromConfidenceTree = lookupMetadata(confidenceData, path, pageKey);
    if (fromConfidenceTree.isNumber()) {
      return normalizeConfidence(fromConfidenceTree.asDouble());
    }
    if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
      return 70;
    }
    return 82;
  }

  private List<Integer> parseBbox(JsonNode evidence, int imageWidth, int imageHeight) {
    JsonNode bbox = firstExisting(evidence, "bbox", "value_bbox", "field_bbox", "region", "box");
    if (bbox.isMissingNode() || bbox.isNull()) {
      return List.of();
    }
    if (bbox.isObject()) {
      double x = number(firstExisting(bbox, "x", "left"));
      double y = number(firstExisting(bbox, "y", "top"));
      double width = number(firstExisting(bbox, "width", "w"));
      double height = number(firstExisting(bbox, "height", "h"));
      return rectToAbsolute(x, y, width, height, imageWidth, imageHeight);
    }
    if (bbox.isArray() && bbox.size() >= 4) {
      double a = bbox.get(0).asDouble();
      double b = bbox.get(1).asDouble();
      double c = bbox.get(2).asDouble();
      double d = bbox.get(3).asDouble();
      if (a <= 1 && b <= 1 && c <= 1 && d <= 1) {
        return rectToAbsolute(a, b, c, d, imageWidth, imageHeight);
      }
      if (looksLikeWidthHeight(a, b, c, d, imageWidth, imageHeight)) {
        return rectToAbsolute(a, b, c, d, imageWidth, imageHeight);
      }
      return clampBbox(List.of((int) Math.round(a), (int) Math.round(b), (int) Math.round(c), (int) Math.round(d)), imageWidth, imageHeight);
    }
    return List.of();
  }

  private List<Integer> rectToAbsolute(double x, double y, double width, double height, int imageWidth, int imageHeight) {
    boolean normalized = x <= 1 && y <= 1 && width <= 1 && height <= 1;
    int left = (int) Math.round(normalized ? x * imageWidth : x);
    int top = (int) Math.round(normalized ? y * imageHeight : y);
    int right = (int) Math.round(left + (normalized ? width * imageWidth : width));
    int bottom = (int) Math.round(top + (normalized ? height * imageHeight : height));
    return clampBbox(List.of(left, top, right, bottom), imageWidth, imageHeight);
  }

  private boolean looksLikeWidthHeight(double x, double y, double width, double height, int imageWidth, int imageHeight) {
    if (width <= 0 || height <= 0) {
      return false;
    }
    if (width <= x || height <= y) {
      return true;
    }
    return width <= imageWidth - x
        && height <= imageHeight - y
        && (width <= imageWidth * 0.8 || height <= imageHeight * 0.25);
  }

  private List<Integer> clampBbox(List<Integer> bbox, int imageWidth, int imageHeight) {
    if (bbox.size() < 4) {
      return List.of();
    }
    int left = Math.max(0, Math.min(imageWidth, bbox.get(0)));
    int top = Math.max(0, Math.min(imageHeight, bbox.get(1)));
    int right = Math.max(0, Math.min(imageWidth, bbox.get(2)));
    int bottom = Math.max(0, Math.min(imageHeight, bbox.get(3)));
    if (right <= left || bottom <= top) {
      return List.of();
    }
    return List.of(left, top, right, bottom);
  }

  private CropResult crop(RenderedOcrPage page, List<Integer> bbox) {
    if (bbox.size() < 4 || page.pngBytes().length == 0) {
      return new CropResult(new byte[0], "");
    }
    try {
      BufferedImage source = ImageIO.read(new ByteArrayInputStream(page.pngBytes()));
      if (source == null) {
        return new CropResult(new byte[0], "");
      }
      int left = Math.max(0, bbox.get(0) - 4);
      int top = Math.max(0, bbox.get(1) - 4);
      int right = Math.min(source.getWidth(), bbox.get(2) + 4);
      int bottom = Math.min(source.getHeight(), bbox.get(3) + 4);
      if (right <= left || bottom <= top) {
        return new CropResult(new byte[0], "");
      }
      BufferedImage crop = source.getSubimage(left, top, right - left, bottom - top);
      BufferedImage rgb = new BufferedImage(crop.getWidth(), crop.getHeight(), BufferedImage.TYPE_INT_RGB);
      Graphics2D graphics = rgb.createGraphics();
      graphics.drawImage(crop, 0, 0, null);
      graphics.dispose();
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      ImageIO.write(rgb, "jpg", output);
      byte[] bytes = output.toByteArray();
      return new CropResult(bytes, "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(bytes));
    } catch (IOException | RuntimeException exception) {
      return new CropResult(new byte[0], "");
    }
  }

  private List<FieldCharacterEvidence> characters(JsonNode evidence, String valueText, List<Integer> bbox) {
    if (valueText.isBlank()) {
      return List.of();
    }
    JsonNode charNodes = firstExisting(evidence, "char_confidences", "characters", "chars");
    List<String> values = splitCodePoints(valueText);
    List<FieldCharacterEvidence> characters = new ArrayList<>();
    if (charNodes.isArray()) {
      for (int index = 0; index < values.size(); index += 1) {
        JsonNode node = index < charNodes.size() ? charNodes.get(index) : NullNode.getInstance();
        String text = firstExisting(node, "char", "text", "value").asText(values.get(index));
        double confidence = node.isMissingNode() ? 100 : normalizeConfidence(firstExisting(node, "confidence", "score").asDouble(100));
        List<Integer> charBbox = parseCharacterBbox(node, bbox, index, values.size());
        characters.add(new FieldCharacterEvidence(index, text, confidence, "ok", charBbox, ""));
      }
      return List.copyOf(characters);
    }

    for (int index = 0; index < values.size(); index += 1) {
      characters.add(new FieldCharacterEvidence(index, values.get(index), 100, "ok", approximateCharBbox(bbox, index, values.size()), ""));
    }
    return List.copyOf(characters);
  }

  private List<Integer> parseCharacterBbox(JsonNode node, List<Integer> fieldBbox, int index, int total) {
    JsonNode bbox = firstExisting(node, "bbox", "box", "region");
    if (bbox.isArray() && bbox.size() >= 4) {
      double a = bbox.get(0).asDouble();
      double b = bbox.get(1).asDouble();
      double c = bbox.get(2).asDouble();
      double d = bbox.get(3).asDouble();
      if (fieldBbox.size() == 4 && a <= 1 && b <= 1 && c <= 1 && d <= 1) {
        int width = fieldBbox.get(2) - fieldBbox.get(0);
        int height = fieldBbox.get(3) - fieldBbox.get(1);
        return List.of(
            fieldBbox.get(0) + (int) Math.round(a * width),
            fieldBbox.get(1) + (int) Math.round(b * height),
            fieldBbox.get(0) + (int) Math.round(c * width),
            fieldBbox.get(1) + (int) Math.round(d * height)
        );
      }
      if (c <= a || d <= b) {
        return List.of(
            (int) Math.round(a),
            (int) Math.round(b),
            (int) Math.round(a + Math.max(1, c)),
            (int) Math.round(b + Math.max(1, d))
        );
      }
      return List.of((int) Math.round(a), (int) Math.round(b), (int) Math.round(c), (int) Math.round(d));
    }
    return approximateCharBbox(fieldBbox, index, total);
  }

  private List<Integer> approximateCharBbox(List<Integer> bbox, int index, int total) {
    if (bbox.size() < 4 || total <= 0) {
      return List.of();
    }
    int width = bbox.get(2) - bbox.get(0);
    int left = bbox.get(0) + Math.round((float) width * index / total);
    int right = bbox.get(0) + Math.round((float) width * (index + 1) / total);
    return List.of(left, bbox.get(1), Math.max(left + 1, right), bbox.get(3));
  }

  private JsonNode firstExisting(JsonNode node, String... keys) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return NullNode.getInstance();
    }
    for (String key : keys) {
      JsonNode value = node.path(key);
      if (!value.isMissingNode()) {
        return value;
      }
    }
    return NullNode.getInstance();
  }

  private double number(JsonNode node) {
    return node.isNumber() ? node.asDouble() : 0;
  }

  private double normalizeConfidence(double value) {
    double normalized = value <= 1 ? value * 100 : value;
    return Math.max(0, Math.min(100, Math.round(normalized)));
  }

  private String label(JsonNode evidence, List<String> path) {
    String label = firstExisting(evidence, "label", "field_label", "name").asText("");
    return label.isBlank() ? humanize(path.isEmpty() ? "field" : path.get(path.size() - 1)) : label;
  }

  private String displayValue(List<String> path, String label, JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) {
      return "未填写";
    }
    if (value.isBoolean()) {
      if (isStandaloneCheckboxState(path, label)) {
        return value.asBoolean() ? "已勾选" : "未勾选";
      }
      return value.asBoolean() ? "有" : "没有";
    }
    String text = value.asText("");
    if (text.isBlank()) {
      return "未填写";
    }
    if (isSignaturePath(path) && "illegible_signature".equalsIgnoreCase(text.trim())) {
      return "签名文字无法辨认";
    }
    if (isSignaturePath(path) && "present".equalsIgnoreCase(text.trim())) {
      return "已签名，未识别出签名文字";
    }
    return text;
  }

  private boolean isStandaloneCheckboxState(List<String> path, String label) {
    String joinedPath = String.join(" ", path).toLowerCase(Locale.ROOT);
    String joinedLabel = label == null ? "" : label.toLowerCase(Locale.ROOT);
    String text = joinedPath + " " + joinedLabel;
    return text.contains("checked")
        || text.contains("checkbox")
        || text.contains("selected")
        || text.contains("is_selected")
        || text.contains("勾选")
        || text.contains("已选");
  }

  private String valueText(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode() || value.isBoolean()) {
      return "";
    }
    return value.asText("");
  }

  private boolean isSignaturePath(List<String> path) {
    return path.stream().anyMatch(part -> part.toLowerCase(Locale.ROOT).contains("signature")
        || part.contains("签名") || part.contains("簽名"));
  }

  private String humanize(String key) {
    return key.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
        .replace('_', ' ')
        .replace('-', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }

  private List<String> splitCodePoints(String text) {
    return text.codePoints()
        .mapToObj(codePoint -> new String(Character.toChars(codePoint)))
        .toList();
  }

  private List<String> append(List<String> path, String key) {
    List<String> next = new ArrayList<>(path);
    next.add(key);
    return List.copyOf(next);
  }

  private record FieldCandidate(List<String> path, JsonNode value) {}

  private record PreparedField(
      int page,
      List<String> path,
      String label,
      JsonNode value,
      String displayValue,
      double confidence,
      List<Integer> bbox,
      CropResult crop,
      String valueText,
      List<FieldCharacterEvidence> characters
  ) {}

  private record CropResult(byte[] bytes, String dataUrl) {}
}
