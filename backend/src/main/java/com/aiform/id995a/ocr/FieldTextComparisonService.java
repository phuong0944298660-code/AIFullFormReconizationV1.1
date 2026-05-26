package com.aiform.id995a.ocr;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FieldTextComparisonService {

  private final FieldTextNormalizer normalizer;

  public FieldTextComparisonService() {
    this(new FieldTextNormalizer());
  }

  public FieldTextComparisonService(FieldTextNormalizer normalizer) {
    this.normalizer = normalizer;
  }

  public List<FieldCharacterEvidence> compare(
      String llmText,
      List<FieldCharacterEvidence> llmCharacters,
      String ocrText
  ) {
    return compare("", "", llmText, llmCharacters, ocrText, 100);
  }

  public List<FieldCharacterEvidence> compare(
      String fieldPath,
      String fieldLabel,
      String llmText,
      List<FieldCharacterEvidence> llmCharacters,
      String ocrText,
      double ocrConfidence
  ) {
    List<FieldCharacterEvidence> characters = llmCharacters == null ? List.of() : llmCharacters;
    if (characters.isEmpty()) {
      characters = charactersFromText(llmText);
    }
    String filteredOcrText = filterApplicantOcrText(fieldPath, fieldLabel, llmText, ocrText);
    MatchEvidence matchEvidence = matchedCharacters(fieldPath, fieldLabel, characters, llmText, filteredOcrText);
    boolean formatValid = normalizer.formatValid(fieldPath, fieldLabel, llmText);
    double normalizedOcrConfidence = normalizeConfidence(ocrConfidence);
    List<FieldCharacterEvidence> reviewed = new ArrayList<>();
    for (int index = 0; index < characters.size(); index += 1) {
      FieldCharacterEvidence character = characters.get(index);
      boolean comparable = matchEvidence.comparable[index];
      boolean mismatch = comparable && filteredOcrText != null && !filteredOcrText.isBlank() && !matchEvidence.matched[index];
      boolean lowQwenConfidence = character.confidence() > 0 && character.confidence() < 70;
      boolean lowOcrScore = comparable && !ocrTextBlank(filteredOcrText) && normalizedOcrConfidence < 70;
      boolean missingBbox = comparable && !hasClearBbox(character.bbox());
      boolean invalidFormat = comparable && !formatValid;
      double confidence = combinedConfidence(character.confidence(), normalizedOcrConfidence, missingBbox, invalidFormat, mismatch);
      String status = status(mismatch, lowQwenConfidence, lowOcrScore, missingBbox, invalidFormat, confidence);
      reviewed.add(new FieldCharacterEvidence(
          character.index(),
          character.text(),
          confidence,
          status,
          character.bbox(),
          mismatch ? "" : character.ocrText()
      ));
    }
    return List.copyOf(reviewed);
  }

  public String filterApplicantOcrText(String fieldPath, String fieldLabel, String llmText, String ocrText) {
    String rawOcrText = ocrText == null ? "" : ocrText.trim();
    if (rawOcrText.isBlank()) {
      return "";
    }
    List<LooseChar> expected = looseChars(llmText);
    List<LooseChar> observed = looseChars(rawOcrText);
    String normalizedExpected = normalizer.normalize(fieldPath, fieldLabel, llmText).value();
    String normalizedObserved = normalizer.normalize(fieldPath, fieldLabel, rawOcrText).value();
    if (!normalizedExpected.isBlank()
        && normalizedExpected.equals(normalizedObserved)
        && observed.size() <= expected.size() + 2) {
      return rawOcrText;
    }
    if (expected.isEmpty() || observed.isEmpty()) {
      return rawOcrText;
    }

    int windowSize = Math.min(expected.size(), observed.size());
    int bestStart = -1;
    int bestScore = -1;
    int bestDistance = Integer.MAX_VALUE;
    for (int start = 0; start < observed.size(); start += 1) {
      int end = Math.min(observed.size(), start + windowSize);
      int score = lcsScore(expected, observed.subList(start, end));
      int distance = Math.abs(start - firstDirectMatch(expected, observed, start, end));
      if (score > bestScore || (score == bestScore && distance < bestDistance)) {
        bestStart = start;
        bestScore = score;
        bestDistance = distance;
      }
    }

    int requiredScore = Math.max(1, Math.min(3, (int) Math.ceil(expected.size() * 0.35)));
    if (bestStart < 0 || bestScore < requiredScore) {
      return rawOcrText;
    }

    int bestEnd = Math.min(observed.size() - 1, bestStart + windowSize - 1);
    int rawStart = observed.get(bestStart).sourceOffset();
    int rawEnd = observed.get(bestEnd).sourceOffset()
        + Character.charCount(rawOcrText.codePointAt(observed.get(bestEnd).sourceOffset()));
    if (rawStart < 0 || rawEnd <= rawStart || rawEnd > rawOcrText.length()) {
      return rawOcrText;
    }
    return rawOcrText.substring(rawStart, rawEnd).trim();
  }

  private List<FieldCharacterEvidence> charactersFromText(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    List<String> values = splitCodePoints(text);
    List<FieldCharacterEvidence> characters = new ArrayList<>();
    for (int index = 0; index < values.size(); index += 1) {
      characters.add(new FieldCharacterEvidence(index, values.get(index), 100, "ok", List.of(), ""));
    }
    return List.copyOf(characters);
  }

  private MatchEvidence matchedCharacters(
      String fieldPath,
      String fieldLabel,
      List<FieldCharacterEvidence> characters,
      String llmText,
      String ocrText
  ) {
    boolean[] matched = new boolean[characters.size()];
    boolean[] comparable = new boolean[characters.size()];
    FieldTextNormalizer.NormalizedText leftText = normalizer.normalize(fieldPath, fieldLabel, llmText);
    FieldTextNormalizer.NormalizedText rightText = normalizer.normalize(fieldPath, fieldLabel, ocrText);
    List<Integer> leftIndexes = new ArrayList<>();
    List<String> left = new ArrayList<>();
    for (FieldTextNormalizer.NormalizedChar character : leftText.chars()) {
      int characterIndex = sourceOffsetToCharacterIndex(llmText, character.sourceOffset());
      if (characterIndex >= 0 && characterIndex < characters.size()) {
        comparable[characterIndex] = true;
        leftIndexes.add(characterIndex);
        left.add(character.value());
      }
    }

    List<String> right = rightText.chars().stream()
        .map(FieldTextNormalizer.NormalizedChar::value)
        .toList();
    if (left.isEmpty() || right.isEmpty()) {
      return new MatchEvidence(matched, comparable);
    }

    boolean[] leftMatched = lcsMatched(left, right);
    for (int index = 0; index < leftMatched.length; index += 1) {
      if (leftMatched[index]) {
        matched[leftIndexes.get(index)] = true;
      }
    }
    return new MatchEvidence(matched, comparable);
  }

  private boolean[] lcsMatched(List<String> left, List<String> right) {
    int[][] dp = new int[left.size() + 1][right.size() + 1];
    for (int i = left.size() - 1; i >= 0; i -= 1) {
      for (int j = right.size() - 1; j >= 0; j -= 1) {
        if (left.get(i).equals(right.get(j))) {
          dp[i][j] = dp[i + 1][j + 1] + 1;
        } else {
          dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
        }
      }
    }

    boolean[] matched = new boolean[left.size()];
    int i = 0;
    int j = 0;
    while (i < left.size() && j < right.size()) {
      if (left.get(i).equals(right.get(j))) {
        matched[i] = true;
        i += 1;
        j += 1;
      } else if (dp[i + 1][j] >= dp[i][j + 1]) {
        i += 1;
      } else {
        j += 1;
      }
    }
    return matched;
  }

  private int lcsScore(List<LooseChar> left, List<LooseChar> right) {
    int[][] dp = new int[left.size() + 1][right.size() + 1];
    for (int i = left.size() - 1; i >= 0; i -= 1) {
      for (int j = right.size() - 1; j >= 0; j -= 1) {
        if (left.get(i).value().equals(right.get(j).value())) {
          dp[i][j] = dp[i + 1][j + 1] + 1;
        } else {
          dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
        }
      }
    }
    return dp[0][0];
  }

  private int firstDirectMatch(List<LooseChar> expected, List<LooseChar> observed, int start, int end) {
    for (int index = start; index < end; index += 1) {
      if (observed.get(index).value().equals(expected.get(0).value())) {
        return index;
      }
    }
    return start;
  }

  private String status(
      boolean mismatch,
      boolean lowQwenConfidence,
      boolean lowOcrScore,
      boolean missingBbox,
      boolean invalidFormat,
      double confidence
  ) {
    List<String> flags = new ArrayList<>();
    if (confidence < 70) {
      flags.add("low_confidence");
    }
    if (mismatch) {
      flags.add("ocr_mismatch");
    }
    if (lowQwenConfidence) {
      flags.add("qwen_low_confidence");
    }
    if (lowOcrScore) {
      flags.add("ocr_low_score");
    }
    if (missingBbox) {
      flags.add("no_bbox_evidence");
    }
    if (invalidFormat) {
      flags.add("format_invalid");
    }
    return flags.isEmpty() ? "ok" : String.join("+", flags);
  }

  private double combinedConfidence(
      double qwenConfidence,
      double ocrConfidence,
      boolean missingBbox,
      boolean invalidFormat,
      boolean mismatch
  ) {
    double confidence = qwenConfidence <= 0 ? 82 : normalizeConfidence(qwenConfidence);
    if (ocrConfidence > 0) {
      confidence = Math.min(confidence, ocrConfidence);
    }
    if (missingBbox) {
      confidence = Math.min(confidence, 65);
    }
    if (invalidFormat) {
      confidence = Math.min(confidence, 55);
    }
    if (mismatch) {
      confidence = Math.min(confidence, 60);
    }
    return Math.max(0, Math.min(100, Math.round(confidence)));
  }

  private double normalizeConfidence(double value) {
    if (value <= 1) {
      return Math.round(value * 100);
    }
    return Math.round(value);
  }

  private boolean hasClearBbox(List<Integer> bbox) {
    return bbox != null && bbox.size() == 4 && bbox.get(2) > bbox.get(0) && bbox.get(3) > bbox.get(1);
  }

  private boolean ocrTextBlank(String value) {
    return value == null || value.isBlank();
  }

  private int sourceOffsetToCharacterIndex(String text, int sourceOffset) {
    if (text == null || sourceOffset < 0 || sourceOffset > text.length()) {
      return -1;
    }
    return text.codePointCount(0, Math.min(sourceOffset, text.length()));
  }

  private List<String> splitCodePoints(String text) {
    if (text == null || text.isEmpty()) {
      return List.of();
    }
    return text.codePoints()
        .mapToObj(codePoint -> new String(Character.toChars(codePoint)))
        .toList();
  }

  private List<LooseChar> looseChars(String text) {
    String normalized = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC);
    List<LooseChar> chars = new ArrayList<>();
    for (int offset = 0; offset < normalized.length();) {
      int codePoint = normalized.codePointAt(offset);
      if (Character.isLetterOrDigit(codePoint) || codePoint == '+') {
        String value = new String(Character.toChars(codePoint)).toUpperCase(Locale.ROOT);
        chars.add(new LooseChar(value, offset));
      }
      offset += Character.charCount(codePoint);
    }
    return List.copyOf(chars);
  }

  private record MatchEvidence(boolean[] matched, boolean[] comparable) {}

  private record LooseChar(String value, int sourceOffset) {}
}
