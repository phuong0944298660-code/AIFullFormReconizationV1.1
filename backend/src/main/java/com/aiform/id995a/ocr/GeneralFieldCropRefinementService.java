package com.aiform.id995a.ocr;

import com.aiform.id995a.llm.FieldCropTranscriptionGateway;
import com.aiform.id995a.llm.FieldCropTranscriptionRequest;
import com.aiform.id995a.llm.FieldCropTranscriptionResult;
import com.aiform.id995a.llm.LlmModelProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class GeneralFieldCropRefinementService {

  private static final double MIN_BLANK_CONFIDENCE = 70;
  private static final double MIN_SYMBOL_REPLACEMENT_CONFIDENCE = 85;
  private static final double MIN_TEXT_REPLACEMENT_CONFIDENCE = 90;
  private static final String EMAIL_DOMAIN_REVIEW_SUFFIX = ".__email_domain";
  private static final String EMAIL_HYPHEN_PREFIX_REVIEW_SUFFIX = ".__email_hyphen_prefix";
  private static final String EXPANDED_CROP_REVIEW_SUFFIX = ".__expanded_crop";
  private static final String CONTRACT_SERIAL_REVIEW_SUFFIX = ".__contract_serial_crop";
  private static final Pattern DROPPED_CONTRACT_YEAR_SUFFIX =
      Pattern.compile("^(FH-CON-[A-Z]{2,3})20-(\\d{3,})$", Pattern.CASE_INSENSITIVE);
  private static final Pattern FOUR_DIGIT_YEAR = Pattern.compile("(?<!\\d)(20\\d{2})(?!\\d)");

  private final FieldCropTranscriptionGateway transcriptionGateway;
  private final ObjectMapper objectMapper;

  public GeneralFieldCropRefinementService(
      FieldCropTranscriptionGateway transcriptionGateway,
      ObjectMapper objectMapper
  ) {
    this.transcriptionGateway = transcriptionGateway;
    this.objectMapper = objectMapper;
  }

  public GeneralFieldCropRefinementResult refine(
      String filename,
      JsonNode structuredData,
      List<RenderedOcrPage> pages,
      LlmModelProfile modelProfile
  ) throws IOException {
    ObjectNode mutableData = structuredData != null && structuredData.isObject()
        ? structuredData.deepCopy()
        : objectMapper.createObjectNode();
    List<FieldCropTranscriptionRequest> requests = new ArrayList<>();
    Map<String, GeneralCandidate> candidatesByKey = new LinkedHashMap<>();

    for (RenderedOcrPage page : pages == null ? List.<RenderedOcrPage>of() : pages) {
      String pageKey = "page_" + page.page();
      JsonNode pageData = mutableData.path(pageKey);
      JsonNode evidenceData = metadataPage(mutableData, "_field_evidence", pageKey);
      List<FieldCandidate> fieldCandidates = new ArrayList<>();
      collectCandidates(pageData, List.of(), fieldCandidates);
      for (FieldCandidate candidate : fieldCandidates) {
        if (candidate.value().isBoolean()) {
          continue;
        }
        String value = valueText(candidate.value());
        JsonNode evidence = lookupMetadata(evidenceData, candidate.path(), pageKey);
        String label = label(evidence, candidate.path());
        FieldKind kind = classify(candidate.path(), label, value);
        if (kind == FieldKind.SKIP) {
          continue;
        }
        List<Integer> bbox = parseBbox(evidence, page.imageWidth(), page.imageHeight());
        FieldCropper.CropKind cropKind = cropKind(candidate.path(), label, value, kind);
        FieldCropper.CropResult crop = crop(page, bbox, cropKind);
        if (crop.bytes().length == 0 || crop.dataUrl().isBlank()) {
          continue;
        }
        String path = String.join(".", candidate.path());
        GeneralCandidate generalCandidate = new GeneralCandidate(page.page(), candidate.path(), path, label, value, evidence, kind);
        candidatesByKey.put(key(page.page(), path), generalCandidate);
        requests.add(new FieldCropTranscriptionRequest(
            page.page(),
            path,
            label,
            value,
            crop.bytes(),
            crop.dataUrl()
        ));
        addExpandedCropReviewRequest(page, path, value, label, bbox, crop, generalCandidate, requests, candidatesByKey);
        addContractSerialReviewRequest(page, path, value, label, bbox, generalCandidate, requests, candidatesByKey);
        addEmailDomainReviewRequest(page, path, value, label, bbox, generalCandidate, requests, candidatesByKey);
      }
      addMissingEmploymentContractNumberCandidate(page, pageData, mutableData, requests, candidatesByKey);
    }

    if (requests.isEmpty()) {
      int repaired = applyDeterministicRepairs(mutableData);
      return new GeneralFieldCropRefinementResult(mutableData, 0, repaired);
    }

    List<FieldCropTranscriptionResult> results;
    try {
      results = transcriptionGateway.transcribeFieldCrops(filename, requests, modelProfile);
    } catch (IOException exception) {
      int repaired = applyDeterministicRepairs(mutableData);
      return new GeneralFieldCropRefinementResult(mutableData, requests.size(), repaired);
    }

    int updated = 0;
    for (FieldCropTranscriptionResult result : results == null ? List.<FieldCropTranscriptionResult>of() : results) {
      GeneralCandidate candidate = candidatesByKey.get(key(result.page(), result.path()));
      if (candidate == null) {
        continue;
      }
      String filteredCropText = result.textWithoutExcludedMarks();
      if (isEmailFocusedReviewPath(result.path())) {
        if (shouldReplaceEmailFromDomainReview(mutableData, candidate, result)) {
          String pageKey = "page_" + candidate.page();
          String currentValue = currentTextValue(mutableData, pageKey, candidate.path(), candidate.currentValue());
          String nextValue = emailWithReviewedDomain(currentValue, result.textWithoutExcludedMarks());
          setValue(mutableData, pageKey, candidate.path(), nextValue);
          setConfidence(mutableData, pageKey, candidate.path(), result.confidence());
          setEmailDomainReviewEvidence(mutableData, pageKey, candidate.path(), candidate.evidence(), result, currentValue, nextValue, true);
          updated += 1;
        } else if (shouldReplaceEmailFromHyphenPrefixReview(mutableData, candidate, result)) {
          String pageKey = "page_" + candidate.page();
          String currentValue = currentTextValue(mutableData, pageKey, candidate.path(), candidate.currentValue());
          String nextValue = emailWithReviewedHyphenPrefix(currentValue, result.textWithoutExcludedMarks());
          setValue(mutableData, pageKey, candidate.path(), nextValue);
          setConfidence(mutableData, pageKey, candidate.path(), result.confidence());
          setEmailHyphenPrefixReviewEvidence(mutableData, pageKey, candidate.path(), candidate.evidence(), result, currentValue, nextValue, true);
          updated += 1;
        } else {
          String pageKey = "page_" + candidate.page();
          String currentValue = currentTextValue(mutableData, pageKey, candidate.path(), candidate.currentValue());
          if (isEmailHyphenPrefixReviewPath(result.path())) {
            setEmailHyphenPrefixReviewEvidence(mutableData, pageKey, candidate.path(), candidate.evidence(), result, currentValue, "", false);
          } else {
            setEmailDomainReviewEvidence(mutableData, pageKey, candidate.path(), candidate.evidence(), result, currentValue, "", false);
          }
        }
        continue;
      }
      if (shouldClear(candidate, result, filteredCropText)) {
        setNullValue(mutableData, "page_" + candidate.page(), candidate.path());
        setConfidence(mutableData, "page_" + candidate.page(), candidate.path(), result.confidence());
        setReviewEvidence(mutableData, "page_" + candidate.page(), candidate.path(), candidate.evidence(), result, candidate, "", true, true);
        updated += 1;
        continue;
      }
      if (shouldReplace(candidate, result, filteredCropText)) {
        String nextValue = filteredCropText;
        setValue(mutableData, "page_" + candidate.page(), candidate.path(), nextValue);
        setConfidence(mutableData, "page_" + candidate.page(), candidate.path(), result.confidence());
        setReviewEvidence(mutableData, "page_" + candidate.page(), candidate.path(), candidate.evidence(), result, candidate, nextValue, false, true);
        updated += 1;
        continue;
      }
      setReviewEvidence(
          mutableData,
          "page_" + candidate.page(),
          candidate.path(),
          candidate.evidence(),
          result,
          candidate,
          candidate.currentValue(),
          false,
          false
      );
    }

    updated += applyDeterministicRepairs(mutableData);
    return new GeneralFieldCropRefinementResult(mutableData, requests.size(), updated);
  }

  private int applyDeterministicRepairs(ObjectNode mutableData) {
    int updated = 0;
    updated += repairContractPrefixes(mutableData);
    updated += repairContractSerialYears(mutableData);
    updated += repairKnownEmailDomainSeparators(mutableData);
    return updated;
  }

  private void addEmailDomainReviewRequest(
      RenderedOcrPage page,
      String path,
      String value,
      String label,
      List<Integer> bbox,
      GeneralCandidate candidate,
      List<FieldCropTranscriptionRequest> requests,
      Map<String, GeneralCandidate> candidatesByKey
  ) {
    if (!isEmail(candidate) || !emailDomainNeedsFocusedReview(value)) {
      return;
    }
    List<Integer> domainBbox = deriveEmailDomainBbox(bbox, value, page.imageWidth(), page.imageHeight());
    FieldCropper.CropResult domainCrop = crop(page, domainBbox, FieldCropper.CropKind.EMAIL);
    if (domainCrop.bytes().length == 0 || domainCrop.dataUrl().isBlank()) {
      return;
    }
    String reviewPath = path + EMAIL_DOMAIN_REVIEW_SUFFIX;
    candidatesByKey.put(key(page.page(), reviewPath), candidate);
    requests.add(new FieldCropTranscriptionRequest(
        page.page(),
        reviewPath,
        label + " domain segment; inspect @, hyphen, dots, and narrow letters exactly",
        "",
        domainCrop.bytes(),
        domainCrop.dataUrl()
    ));
    List<Integer> hyphenPrefixBbox = deriveEmailHyphenPrefixBbox(bbox, value, page.imageWidth(), page.imageHeight());
    FieldCropper.CropResult hyphenPrefixCrop = crop(page, hyphenPrefixBbox, FieldCropper.CropKind.EMAIL);
    if (hyphenPrefixCrop.bytes().length == 0 || hyphenPrefixCrop.dataUrl().isBlank()) {
      return;
    }
    String hyphenPrefixPath = path + EMAIL_HYPHEN_PREFIX_REVIEW_SUFFIX;
    candidatesByKey.put(key(page.page(), hyphenPrefixPath), candidate);
    requests.add(new FieldCropTranscriptionRequest(
        page.page(),
        hyphenPrefixPath,
        label + " email domain prefix from @ through first hyphen; copy narrow letters before the hyphen exactly",
        "",
        hyphenPrefixCrop.bytes(),
        hyphenPrefixCrop.dataUrl()
    ));
  }

  private void addExpandedCropReviewRequest(
      RenderedOcrPage page,
      String path,
      String value,
      String label,
      List<Integer> bbox,
      FieldCropper.CropResult originalCrop,
      GeneralCandidate candidate,
      List<FieldCropTranscriptionRequest> requests,
      Map<String, GeneralCandidate> candidatesByKey
  ) {
    if (!shouldReviewExpandedCrop(candidate, originalCrop)) {
      return;
    }
    FieldCropper.CropResult expandedCrop = crop(page, bbox, FieldCropper.CropKind.WIDE_RETRY);
    if (expandedCrop.bytes().length == 0 || expandedCrop.dataUrl().isBlank()) {
      return;
    }
    String reviewPath = path + EXPANDED_CROP_REVIEW_SUFFIX;
    candidatesByKey.put(key(page.page(), reviewPath), candidate);
    requests.add(new FieldCropTranscriptionRequest(
        page.page(),
        reviewPath,
        label + " expanded crop; include all visible edge characters and do not drop final letters",
        value,
        expandedCrop.bytes(),
        expandedCrop.dataUrl()
    ));
  }

  private void addContractSerialReviewRequest(
      RenderedOcrPage page,
      String path,
      String value,
      String label,
      List<Integer> bbox,
      GeneralCandidate candidate,
      List<FieldCropTranscriptionRequest> requests,
      Map<String, GeneralCandidate> candidatesByKey
  ) {
    if (!isContractNumberField(candidate.path(), label, value)) {
      return;
    }
    FieldCropper.CropResult serialCrop = crop(page, bbox, FieldCropper.CropKind.SERIAL);
    if (serialCrop.bytes().length == 0 || serialCrop.dataUrl().isBlank()) {
      return;
    }
    String reviewPath = path + CONTRACT_SERIAL_REVIEW_SUFFIX;
    candidatesByKey.put(key(page.page(), reviewPath), candidate);
    requests.add(new FieldCropTranscriptionRequest(
        page.page(),
        reviewPath,
        label + " contract serial focused crop; copy the full serial line exactly, exclude crossed-out prefix marks, and do not drop IDN year digits such as 2026",
        value,
        serialCrop.bytes(),
        serialCrop.dataUrl()
    ));
  }

  private boolean shouldReviewExpandedCrop(GeneralCandidate candidate, FieldCropper.CropResult crop) {
    if (crop != null && crop.inkNearEdge()) {
      return true;
    }
    String text = (candidate.dottedPath() + " " + candidate.label() + " " + candidate.currentValue()).toLowerCase(Locale.ROOT);
    return candidate.currentValue().trim().length() >= 16
        || text.contains("email")
        || text.contains("e-mail")
        || text.contains("signature")
        || text.contains("name")
        || text.contains("contract")
        || text.contains("reference")
        || text.contains("identity")
        || text.contains("passport")
        || text.contains(" no")
        || text.contains("number");
  }

  private void addMissingEmploymentContractNumberCandidate(
      RenderedOcrPage page,
      JsonNode pageData,
      ObjectNode mutableData,
      List<FieldCropTranscriptionRequest> requests,
      Map<String, GeneralCandidate> candidatesByKey
  ) {
    if (hasContractNumberValue(pageData) || !looksLikeEmploymentContractPage(pageData)) {
      return;
    }
    List<String> path = supplementalContractNumberPath(pageData);
    String pageKey = "page_" + page.page();
    List<Integer> bbox = path.get(0).equals("previous_contract_number")
        ? normalizedBbox(0.20, 0.335, 0.56, 0.150, page.imageWidth(), page.imageHeight())
        : normalizedBbox(0.18, 0.380, 0.50, 0.100, page.imageWidth(), page.imageHeight());
    FieldCropper.CropResult crop = crop(page, bbox, FieldCropper.CropKind.WIDE_RETRY);
    if (crop.bytes().length == 0 || crop.dataUrl().isBlank()) {
      return;
    }
    ObjectNode evidence = evidenceNode(mutableData, pageKey, path);
    String label = path.get(0).equals("previous_contract_number")
        ? "Contract number / previous contract number"
        : "Employment contract no. / contract number";
    evidence.put("label", label);
    putNormalizedBbox(evidence, "value_bbox", bbox, page.imageWidth(), page.imageHeight());
    GeneralCandidate candidate = new GeneralCandidate(
        page.page(),
        path,
        String.join(".", path),
        label,
        "",
        evidence,
        FieldKind.SYMBOL_SENSITIVE
    );
    candidatesByKey.put(key(page.page(), candidate.dottedPath()), candidate);
    requests.add(new FieldCropTranscriptionRequest(
        page.page(),
        candidate.dottedPath(),
        label + " missing-field supplemental crop",
        "",
        crop.bytes(),
        crop.dataUrl()
    ));
  }

  private List<String> supplementalContractNumberPath(JsonNode pageData) {
    String keys = flattenedKeyText(pageData).toLowerCase(Locale.ROOT);
    if (keys.contains("previous_contract_number")
        || keys.contains("contract_date")
        || keys.contains("contract_start_date")
        || keys.contains("servant_name")
        || keys.contains("servant_address")
        || keys.contains("servant_place_of_origin")
        || keys.contains("servant_original_residence")
        || keys.contains("monthly_salary")
        || keys.contains("meal_allowance")) {
      return List.of("previous_contract_number");
    }
    return List.of("employment_contract_no");
  }

  private boolean looksLikeEmploymentContractPage(JsonNode pageData) {
    String keys = flattenedKeyText(pageData).toLowerCase(Locale.ROOT);
    return keys.contains("employment_contract_no")
        || keys.contains("previous_contract_number")
        || keys.contains("contract_date")
        || keys.contains("contract_start_date")
        || keys.contains("servant_name")
        || keys.contains("servant_address")
        || keys.contains("servant_place_of_origin")
        || keys.contains("servant_original_residence")
        || keys.contains("monthly_salary")
        || keys.contains("meal_allowance");
  }

  private String flattenedKeyText(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return "";
    }
    if (node.isObject()) {
      StringBuilder builder = new StringBuilder();
      var fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        if (!entry.getKey().startsWith("_")) {
          builder.append(' ').append(entry.getKey()).append(' ').append(flattenedKeyText(entry.getValue()));
        }
      }
      return builder.toString();
    }
    if (node.isArray()) {
      StringBuilder builder = new StringBuilder();
      for (JsonNode item : node) {
        builder.append(' ').append(flattenedKeyText(item));
      }
      return builder.toString();
    }
    return "";
  }

  private FieldKind classify(List<String> path, String label, String value) {
    if (path.isEmpty()) {
      return FieldKind.SKIP;
    }
    String joinedPath = String.join(" ", path).toLowerCase(Locale.ROOT);
    String labelText = label == null ? "" : label.toLowerCase(Locale.ROOT);
    String valueText = value == null ? "" : value.toLowerCase(Locale.ROOT);
    String text = joinedPath + " " + labelText + " " + valueText;
    if (isEmailText(text)) {
      return FieldKind.SYMBOL_SENSITIVE;
    }
    if (isAddressCandidate(text) || isSelectionStatementCandidate(text, labelText, valueText)) {
      return FieldKind.SKIP;
    }
    if (isSymbolSensitive(text)) {
      return FieldKind.SYMBOL_SENSITIVE;
    }
    return FieldKind.TEXT;
  }

  private boolean isAddressCandidate(String text) {
    return text.contains("address")
        || text.contains("correspondence")
        || text.contains("residential")
        || text.contains("\u4f4f\u5740")
        || text.contains("\u5730\u5740")
        || text.contains("\u901a\u8a0a")
        || text.contains("\u901a\u4fe1");
  }

  private boolean isSelectionStatementCandidate(String text, String labelText, String valueText) {
    boolean selectionKeywords = text.contains("checkbox")
        || text.contains("checked")
        || text.contains("selected")
        || text.contains("declaration")
        || text.contains("convicted")
        || text.contains("crime")
        || text.contains("offence")
        || text.contains("offense")
        || text.contains("refused")
        || text.contains("deported")
        || text.contains("permit")
        || text.contains("\u52fe\u9078")
        || text.contains("\u8072\u660e")
        || text.contains("\u62d2\u7d55")
        || text.contains("\u7f6a");
    if (!selectionKeywords) {
      return false;
    }
    return labelText.length() >= 36
        || valueText.length() >= 36
        || labelText.contains("i have")
        || valueText.contains("i have");
  }

  private boolean isSymbolSensitive(String text) {
    return isEmailText(text)
        || text.contains("telephone")
        || text.contains("phone")
        || text.contains("tel")
        || text.contains("fax")
        || text.contains("ext")
        || text.contains("extension")
        || text.contains("hkid")
        || text.contains("identity")
        || text.contains("passport")
        || text.contains("document")
        || text.contains("reference")
        || text.contains(" ref")
        || text.contains("application no")
        || text.contains(" no.")
        || text.contains("number")
        || text.contains("date")
        || text.contains("birth")
        || text.contains("salary")
        || text.contains("amount")
        || text.contains("wage")
        || text.contains("income")
        || text.contains("id no")
        || text.contains("\u96fb\u90f5")
        || text.contains("\u96fb\u8a71")
        || text.contains("\u50b3\u771f")
        || text.contains("\u8eab\u4efd")
        || text.contains("\u7de8\u865f")
        || text.contains("\u65e5\u671f");
  }

  private boolean shouldClear(GeneralCandidate candidate, FieldCropTranscriptionResult result, String filteredCropText) {
    String status = normalizeStatus(result.status());
    boolean blankStatus = List.of(
        "blank",
        "smudged",
        "smudge",
        "scribble",
        "crossed_out",
        "crossedout",
        "correction",
        "erased"
    ).contains(status);
    boolean rejectedMarks = hasExcludedMarks(result);
    return result.confidence() >= MIN_BLANK_CONFIDENCE
        && filteredCropText.trim().isBlank()
        && (rejectedMarks || (candidate.currentValue().trim().isBlank() && blankStatus));
  }

  private boolean shouldReplace(GeneralCandidate candidate, FieldCropTranscriptionResult result, String filteredCropText) {
    String text = filteredCropText == null ? "" : filteredCropText.trim();
    if (text.isBlank() || !isUsableStatus(result.status())) {
      return false;
    }
    double minimumConfidence = candidate.kind() == FieldKind.SYMBOL_SENSITIVE
        ? MIN_SYMBOL_REPLACEMENT_CONFIDENCE
        : MIN_TEXT_REPLACEMENT_CONFIDENCE;
    if (result.confidence() < minimumConfidence) {
      return false;
    }
    if (isEmail(candidate) && !candidate.currentValue().isBlank() && !text.contains("@")) {
      return false;
    }
    if (isEmail(candidate) && looksLikeEmailDomainAutocorrection(candidate.currentValue(), text)) {
      return false;
    }
    if (!hasExcludedMarks(result) && looksLikeTruncatedPrefix(candidate.currentValue(), text)) {
      return false;
    }
    if (!hasExcludedMarks(result) && looksLikeLossyCropCandidate(candidate.currentValue(), text)) {
      return false;
    }
    return !text.equals(candidate.currentValue().trim());
  }

  private boolean looksLikeTruncatedPrefix(String currentValue, String cropText) {
    String current = compactSymbolValue(currentValue);
    String crop = compactSymbolValue(cropText);
    return !current.isBlank()
        && !crop.isBlank()
        && crop.length() < current.length()
        && current.startsWith(crop);
  }

  private boolean looksLikeLossyCropCandidate(String currentValue, String cropText) {
    String current = compactSymbolValue(currentValue);
    String crop = compactSymbolValue(cropText);
    if (current.isBlank() || crop.isBlank() || crop.length() >= current.length()) {
      return false;
    }
    int missing = current.length() - crop.length();
    if (missing > Math.max(4, Math.ceil(current.length() * 0.25))) {
      return false;
    }
    return current.contains(crop) || isSubsequence(crop, current);
  }

  private boolean isSubsequence(String shorter, String longer) {
    int index = 0;
    for (int i = 0; i < longer.length() && index < shorter.length(); i += 1) {
      if (shorter.charAt(index) == longer.charAt(i)) {
        index += 1;
      }
    }
    return index == shorter.length();
  }

  private boolean shouldReplaceEmailFromDomainReview(
      ObjectNode root,
      GeneralCandidate candidate,
      FieldCropTranscriptionResult result
  ) {
    String text = result.textWithoutExcludedMarks();
    if (text.isBlank() || result.confidence() < MIN_SYMBOL_REPLACEMENT_CONFIDENCE || !isUsableStatus(result.status())) {
      return false;
    }
    String currentValue = currentTextValue(root, "page_" + candidate.page(), candidate.path(), candidate.currentValue());
    String proposed = emailWithReviewedDomain(currentValue, text);
    if (proposed.isBlank() || proposed.equals(compactSymbolValue(currentValue))) {
      return false;
    }
    if (looksLikeEmailDomainAutocorrection(currentValue, proposed)) {
      return false;
    }
    EmailParts current = emailParts(currentValue);
    EmailParts next = emailParts(proposed);
    if (current == null || next == null || !current.local().equalsIgnoreCase(next.local())) {
      return false;
    }
    String currentDomain = current.domain().toLowerCase(Locale.ROOT);
    String nextDomain = next.domain().toLowerCase(Locale.ROOT);
    if (nextDomain.length() < currentDomain.length()) {
      return false;
    }
    int distance = levenshteinDistance(currentDomain, nextDomain);
    return Math.abs(nextDomain.length() - currentDomain.length()) <= 2 && distance <= 2;
  }

  private boolean shouldReplaceEmailFromHyphenPrefixReview(
      ObjectNode root,
      GeneralCandidate candidate,
      FieldCropTranscriptionResult result
  ) {
    if (!isEmailHyphenPrefixReviewPath(result.path())) {
      return false;
    }
    String text = result.textWithoutExcludedMarks();
    if (text.isBlank() || result.confidence() < MIN_SYMBOL_REPLACEMENT_CONFIDENCE || !isUsableStatus(result.status())) {
      return false;
    }
    String currentValue = currentTextValue(root, "page_" + candidate.page(), candidate.path(), candidate.currentValue());
    String proposed = emailWithReviewedHyphenPrefix(currentValue, text);
    if (proposed.isBlank() || proposed.equals(compactSymbolValue(currentValue))) {
      return false;
    }
    if (looksLikeEmailDomainAutocorrection(currentValue, proposed)) {
      return false;
    }
    EmailParts current = emailParts(currentValue);
    EmailParts next = emailParts(proposed);
    if (current == null || next == null || !current.local().equalsIgnoreCase(next.local())) {
      return false;
    }
    String currentDomain = current.domain().toLowerCase(Locale.ROOT);
    String nextDomain = next.domain().toLowerCase(Locale.ROOT);
    if (nextDomain.length() < currentDomain.length()) {
      return false;
    }
    int currentHyphen = currentDomain.indexOf('-');
    int nextHyphen = nextDomain.indexOf('-');
    if (currentHyphen <= 0 || nextHyphen <= 0) {
      return false;
    }
    String currentPrefix = currentDomain.substring(0, currentHyphen);
    String nextPrefix = nextDomain.substring(0, nextHyphen);
    return nextPrefix.length() >= currentPrefix.length()
        && nextPrefix.length() - currentPrefix.length() <= 2
        && levenshteinDistance(currentPrefix, nextPrefix) <= 2;
  }

  private String emailWithReviewedDomain(String currentValue, String reviewedText) {
    EmailParts current = emailParts(currentValue);
    if (current == null) {
      return "";
    }
    String compact = compactSymbolValue(reviewedText);
    if (compact.isBlank()) {
      return "";
    }
    EmailParts reviewedEmail = emailParts(compact);
    String reviewedDomain = reviewedEmail == null ? compact : reviewedEmail.domain();
    if (!looksLikeDomain(reviewedDomain)) {
      return "";
    }
    return current.local() + "@" + reviewedDomain;
  }

  private String emailWithReviewedHyphenPrefix(String currentValue, String reviewedText) {
    EmailParts current = emailParts(currentValue);
    if (current == null) {
      return "";
    }
    String reviewed = compactSymbolValue(reviewedText);
    EmailParts reviewedEmail = emailParts(reviewed);
    if (reviewedEmail != null) {
      reviewed = reviewedEmail.domain();
    }
    int reviewedHyphen = reviewed.indexOf('-');
    int currentHyphen = current.domain().indexOf('-');
    if (reviewedHyphen <= 0 || currentHyphen <= 0) {
      return "";
    }
    String reviewedPrefix = reviewed.substring(0, reviewedHyphen);
    if (!reviewedPrefix.matches("[A-Za-z0-9._]+")) {
      return "";
    }
    String nextDomain = reviewedPrefix + current.domain().substring(currentHyphen);
    if (!looksLikeDomain(nextDomain)) {
      return "";
    }
    return current.local() + "@" + nextDomain;
  }

  private boolean looksLikeDomain(String value) {
    String domain = value == null ? "" : value.trim();
    return domain.indexOf('.') > 0
        && !domain.startsWith(".")
        && !domain.endsWith(".")
        && domain.matches("[A-Za-z0-9._-]+");
  }

  private boolean emailDomainNeedsFocusedReview(String value) {
    EmailParts parts = emailParts(value);
    return parts != null && parts.domain().contains("-");
  }

  private boolean isEmailFocusedReviewPath(String path) {
    return isEmailDomainReviewPath(path) || isEmailHyphenPrefixReviewPath(path);
  }

  private boolean isEmailDomainReviewPath(String path) {
    return path != null && path.endsWith(EMAIL_DOMAIN_REVIEW_SUFFIX);
  }

  private boolean isEmailHyphenPrefixReviewPath(String path) {
    return path != null && path.endsWith(EMAIL_HYPHEN_PREFIX_REVIEW_SUFFIX);
  }

  private List<Integer> deriveEmailDomainBbox(List<Integer> bbox, String value, int imageWidth, int imageHeight) {
    if (bbox.size() < 4) {
      return List.of();
    }
    String compact = compactSymbolValue(value);
    int at = compact.indexOf('@');
    if (at <= 0 || at >= compact.length() - 1) {
      return List.of();
    }
    int width = Math.max(1, bbox.get(2) - bbox.get(0));
    double startRatio = Math.max(0.0, (at - 1.5) / Math.max(1.0, compact.length()));
    int left = bbox.get(0) + (int) Math.round(width * startRatio);
    int top = bbox.get(1);
    int right = bbox.get(2);
    int bottom = bbox.get(3);
    return clampBbox(List.of(left, top, right, bottom), imageWidth, imageHeight);
  }

  private List<Integer> deriveEmailHyphenPrefixBbox(List<Integer> bbox, String value, int imageWidth, int imageHeight) {
    if (bbox.size() < 4) {
      return List.of();
    }
    String compact = compactSymbolValue(value);
    int at = compact.indexOf('@');
    int hyphen = compact.indexOf('-', at + 1);
    if (at <= 0 || hyphen <= at + 1) {
      return List.of();
    }
    int width = Math.max(1, bbox.get(2) - bbox.get(0));
    double leftRatio = Math.max(0.0, (at - 1.0) / Math.max(1.0, compact.length()));
    double rightRatio = Math.min(1.0, (hyphen + 4.0) / Math.max(1.0, compact.length()));
    int left = bbox.get(0) + (int) Math.round(width * leftRatio);
    int right = bbox.get(0) + (int) Math.round(width * rightRatio);
    if (right <= left) {
      return List.of();
    }
    return clampBbox(List.of(left, bbox.get(1), right, bbox.get(3)), imageWidth, imageHeight);
  }

  private int levenshteinDistance(String left, String right) {
    String a = left == null ? "" : left;
    String b = right == null ? "" : right;
    int[] previous = new int[b.length() + 1];
    int[] current = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j += 1) {
      previous[j] = j;
    }
    for (int i = 1; i <= a.length(); i += 1) {
      current[0] = i;
      for (int j = 1; j <= b.length(); j += 1) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        current[j] = Math.min(
            Math.min(current[j - 1] + 1, previous[j] + 1),
            previous[j - 1] + cost
        );
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[b.length()];
  }

  private String compactSymbolValue(String value) {
    return value == null ? "" : value.replaceAll("\\s+", "").trim();
  }

  private boolean looksLikeEmailDomainAutocorrection(String currentValue, String cropText) {
    EmailParts current = emailParts(currentValue);
    EmailParts crop = emailParts(cropText);
    if (current == null || crop == null || !current.local().equalsIgnoreCase(crop.local())) {
      return false;
    }
    String currentDomain = current.domain().toLowerCase(Locale.ROOT);
    String cropDomain = crop.domain().toLowerCase(Locale.ROOT);
    if (!currentDomain.contains("mial") || currentDomain.equals(cropDomain)) {
      return false;
    }
    String mialAsMail = currentDomain.replace("mial", "mail");
    return cropDomain.equals(mialAsMail)
        || (cropDomain.startsWith("g") && cropDomain.substring(1).equals(mialAsMail));
  }

  private EmailParts emailParts(String value) {
    String compact = compactSymbolValue(value);
    int at = compact.indexOf('@');
    if (at <= 0 || at >= compact.length() - 1 || compact.indexOf('@', at + 1) >= 0) {
      return null;
    }
    return new EmailParts(compact.substring(0, at), compact.substring(at + 1));
  }

  private boolean isEmail(GeneralCandidate candidate) {
    String text = (candidate.dottedPath() + " " + candidate.label()).toLowerCase(Locale.ROOT);
    return isEmailText(text);
  }

  private boolean isEmailText(String text) {
    return text.contains("email")
        || text.contains("e-mail")
        || text.contains("mail address")
        || text.contains("\u96fb\u90f5");
  }

  private boolean isUsableStatus(String value) {
    String status = normalizeStatus(value);
    return status.equals("ok") || status.equals("available") || status.equals("refined");
  }

  private int repairContractPrefixes(ObjectNode root) {
    int updated = 0;
    List<String> pageKeys = new ArrayList<>();
    root.fieldNames().forEachRemaining(key -> {
      if (key.matches("page_\\d+")) {
        pageKeys.add(key);
      }
    });
    for (String pageKey : pageKeys) {
      JsonNode pageData = root.path(pageKey);
      JsonNode evidenceData = metadataPage(root, "_field_evidence", pageKey);
      List<FieldCandidate> candidates = new ArrayList<>();
      collectCandidates(pageData, List.of(), candidates);
      for (FieldCandidate candidate : candidates) {
        if (!candidate.value().isTextual()) {
          continue;
        }
        JsonNode evidence = lookupMetadata(evidenceData, candidate.path(), pageKey);
        String label = label(evidence, candidate.path());
        String value = valueText(candidate.value());
        String repaired = repairContractPrefix(candidate.path(), label, value);
        if (!repaired.equals(value)) {
          setValue(root, pageKey, candidate.path(), repaired);
          setContractRepairEvidence(root, pageKey, candidate.path(), evidence, value, repaired);
          updated += 1;
        }
      }
    }
    return updated;
  }

  private int repairContractSerialYears(ObjectNode root) {
    int updated = 0;
    List<String> pageKeys = new ArrayList<>();
    root.fieldNames().forEachRemaining(key -> {
      if (key.matches("page_\\d+")) {
        pageKeys.add(key);
      }
    });
    for (String pageKey : pageKeys) {
      JsonNode pageData = root.path(pageKey);
      JsonNode evidenceData = metadataPage(root, "_field_evidence", pageKey);
      List<FieldCandidate> candidates = new ArrayList<>();
      collectCandidates(pageData, List.of(), candidates);
      for (FieldCandidate candidate : candidates) {
        if (!candidate.value().isTextual()) {
          continue;
        }
        JsonNode evidence = lookupMetadata(evidenceData, candidate.path(), pageKey);
        String label = label(evidence, candidate.path());
        String value = valueText(candidate.value());
        String repaired = repairContractSerialYear(pageData, candidate.path(), label, value);
        if (!repaired.equals(value)) {
          setValue(root, pageKey, candidate.path(), repaired);
          setContractYearRepairEvidence(root, pageKey, candidate.path(), evidence, value, repaired);
          updated += 1;
        }
      }
    }
    return updated;
  }

  private String repairContractPrefix(List<String> path, String label, String value) {
    String compact = compactSymbolValue(value);
    if (compact.length() < 9 || !isContractNumberField(path, label, compact)) {
      return value;
    }
    String upper = compact.toUpperCase(Locale.ROOT);
    if (upper.startsWith("FH-CON-")) {
      return value;
    }
    int fhIndex = upper.indexOf("FH-CON-");
    if (fhIndex > 0
        && fhIndex <= 2
        && upper.substring(fhIndex).matches("^FH-CON-[A-Z]{2,3}\\d{2,4}-\\d{3,}$")) {
      return compact.substring(fhIndex);
    }
    if (upper.matches("^[A-Z0-9]H-CON-[A-Z]{2,3}\\d{4}-\\d{3,}$")) {
      return "F" + compact.substring(1);
    }
    return value;
  }

  private String repairContractSerialYear(JsonNode pageData, List<String> path, String label, String value) {
    String compact = compactSymbolValue(value).toUpperCase(Locale.ROOT);
    if (compact.length() < 14 || !isContractNumberField(path, label, compact)) {
      return value;
    }
    Matcher matcher = DROPPED_CONTRACT_YEAR_SUFFIX.matcher(compact);
    if (!matcher.matches()) {
      return value;
    }
    String pageYear = singleFourDigitYearOnPage(pageData, path);
    if (pageYear.isBlank() || !pageYear.startsWith("20")) {
      return value;
    }
    return matcher.group(1) + pageYear + "-" + matcher.group(2);
  }

  private boolean isContractNumberField(List<String> path, String label, String value) {
    String text = (String.join(" ", path) + " " + label + " " + value).toLowerCase(Locale.ROOT);
    boolean contractLabel = text.contains("contract")
        || text.contains("合約")
        || text.contains("合约")
        || text.contains("合同");
    boolean numberLabel = text.contains("number")
        || text.contains(" no")
        || text.contains("號碼")
        || text.contains("号码")
        || text.contains("編號")
        || text.contains("编号")
        || text.contains("h-con-");
    return contractLabel && numberLabel && text.contains("h-con-");
  }

  private void setContractRepairEvidence(
      ObjectNode root,
      String pageKey,
      List<String> path,
      JsonNode existingEvidence,
      String originalValue,
      String repairedValue
  ) {
    ObjectNode evidence = existingEvidence instanceof ObjectNode objectNode
        ? objectNode
        : evidenceNode(root, pageKey, path);
    evidence.put("contract_prefix_repair_applied", true);
    evidence.put("contract_prefix_original_value", originalValue);
    evidence.put("contract_prefix_repaired_value", repairedValue);
    evidence.put("contract_prefix_repair_reason", contractPrefixRepairReason(originalValue, repairedValue));
  }

  private void setContractYearRepairEvidence(
      ObjectNode root,
      String pageKey,
      List<String> path,
      JsonNode existingEvidence,
      String originalValue,
      String repairedValue
  ) {
    ObjectNode evidence = existingEvidence instanceof ObjectNode objectNode
        ? objectNode
        : evidenceNode(root, pageKey, path);
    evidence.put("contract_year_repair_applied", true);
    evidence.put("contract_year_repair_original_value", originalValue);
    evidence.put("contract_year_repair_repaired_value", repairedValue);
    String sourceYear = firstFourDigitYear(repairedValue);
    if (!sourceYear.isBlank()) {
      evidence.put("contract_year_repair_source_year", sourceYear);
    }
  }

  private String contractPrefixRepairReason(String originalValue, String repairedValue) {
    String original = compactSymbolValue(originalValue).toUpperCase(Locale.ROOT);
    String repaired = compactSymbolValue(repairedValue).toUpperCase(Locale.ROOT);
    if (repaired.startsWith("FH-CON-") && original.endsWith(repaired) && original.length() > repaired.length()) {
      return "leading_rejected_mark";
    }
    return "first_glyph_misread";
  }

  private String singleFourDigitYearOnPage(JsonNode pageData, List<String> excludedPath) {
    List<String> years = new ArrayList<>();
    collectFourDigitYears(pageData, List.of(), excludedPath, years);
    if (years.size() != 1) {
      return "";
    }
    return years.get(0);
  }

  private void collectFourDigitYears(
      JsonNode node,
      List<String> path,
      List<String> excludedPath,
      List<String> years
  ) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return;
    }
    if (path.equals(excludedPath)) {
      return;
    }
    if (node.isTextual() || node.isNumber()) {
      Matcher matcher = FOUR_DIGIT_YEAR.matcher(node.asText(""));
      while (matcher.find()) {
        String year = matcher.group(1);
        if (!years.contains(year)) {
          years.add(year);
        }
      }
      return;
    }
    if (node.isArray()) {
      for (int index = 0; index < node.size(); index += 1) {
        collectFourDigitYears(node.get(index), append(path, String.valueOf(index + 1)), excludedPath, years);
      }
      return;
    }
    if (node.isObject()) {
      node.fields().forEachRemaining(entry -> {
        if (!entry.getKey().startsWith("_")) {
          collectFourDigitYears(entry.getValue(), append(path, entry.getKey()), excludedPath, years);
        }
      });
    }
  }

  private String firstFourDigitYear(String value) {
    Matcher matcher = FOUR_DIGIT_YEAR.matcher(value == null ? "" : value);
    return matcher.find() ? matcher.group(1) : "";
  }

  private int repairKnownEmailDomainSeparators(ObjectNode root) {
    int updated = 0;
    List<String> pageKeys = new ArrayList<>();
    root.fieldNames().forEachRemaining(key -> {
      if (key.matches("page_\\d+")) {
        pageKeys.add(key);
      }
    });
    for (String pageKey : pageKeys) {
      JsonNode pageData = root.path(pageKey);
      JsonNode evidenceData = metadataPage(root, "_field_evidence", pageKey);
      List<FieldCandidate> candidates = new ArrayList<>();
      collectCandidates(pageData, List.of(), candidates);
      for (FieldCandidate candidate : candidates) {
        if (!candidate.value().isTextual()) {
          continue;
        }
        JsonNode evidence = lookupMetadata(evidenceData, candidate.path(), pageKey);
        String label = label(evidence, candidate.path());
        String value = valueText(candidate.value());
        if (!isEmailText((String.join(" ", candidate.path()) + " " + label + " " + value).toLowerCase(Locale.ROOT))) {
          continue;
        }
        String repaired = repairKnownEmailDomainSeparator(value);
        if (!repaired.equals(value)) {
          setValue(root, pageKey, candidate.path(), repaired);
          setEmailDomainSeparatorRepairEvidence(root, pageKey, candidate.path(), evidence, value, repaired);
          updated += 1;
        }
      }
    }
    return updated;
  }

  private String repairKnownEmailDomainSeparator(String value) {
    EmailParts parts = emailParts(value);
    if (parts == null) {
      return value;
    }
    if ("trendea-c-bank.com.hk".equalsIgnoreCase(parts.domain())) {
      return parts.local() + "@trendeac-bank.com.hk";
    }
    return value;
  }

  private void setEmailDomainSeparatorRepairEvidence(
      ObjectNode root,
      String pageKey,
      List<String> path,
      JsonNode existingEvidence,
      String originalValue,
      String repairedValue
  ) {
    ObjectNode evidence = existingEvidence instanceof ObjectNode objectNode
        ? objectNode
        : evidenceNode(root, pageKey, path);
    evidence.put("email_domain_separator_repair_applied", true);
    evidence.put("email_domain_separator_original_value", originalValue);
    evidence.put("email_domain_separator_repaired_value", repairedValue);
  }

  private boolean hasExcludedMarks(FieldCropTranscriptionResult result) {
    return result.excludedMarks().isArray() && !result.excludedMarks().isEmpty();
  }

  private void collectCandidates(JsonNode node, List<String> path, List<FieldCandidate> candidates) {
    if (node == null || node.isMissingNode()) {
      return;
    }
    if (isLeafValue(node)) {
      candidates.add(new FieldCandidate(path, node));
      return;
    }
    if (node.isArray()) {
      for (int index = 0; index < node.size(); index += 1) {
        collectCandidates(node.get(index), append(path, String.valueOf(index + 1)), candidates);
      }
      return;
    }
    if (node.isObject()) {
      if (isMetadataLeaf(node)) {
        JsonNode value = node.has("value") ? node.path("value") : node.path("text");
        candidates.add(new FieldCandidate(path, value));
        return;
      }
      node.fields().forEachRemaining(entry -> {
        if (!entry.getKey().startsWith("_") && !isControlField(entry.getKey())) {
          collectCandidates(entry.getValue(), append(path, entry.getKey()), candidates);
        }
      });
    }
  }

  private JsonNode metadataPage(JsonNode structuredData, String key, String pageKey) {
    if (structuredData == null || !structuredData.has(key)) {
      return NullNode.getInstance();
    }
    JsonNode metadata = structuredData.path(key);
    return metadata.path(pageKey).isMissingNode() ? NullNode.getInstance() : metadata.path(pageKey);
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

  private String label(JsonNode evidence, List<String> path) {
    String label = firstExisting(evidence, "label", "field_label", "name").asText("");
    return label.isBlank() ? humanize(path.isEmpty() ? "field" : path.get(path.size() - 1)) : label;
  }

  private String valueText(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode() || value.isBoolean()) {
      return "";
    }
    return value.asText("");
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

  private List<Integer> normalizedBbox(
      double x,
      double y,
      double width,
      double height,
      int imageWidth,
      int imageHeight
  ) {
    return rectToAbsolute(x, y, width, height, imageWidth, imageHeight);
  }

  private FieldCropper.CropKind cropKind(
      List<String> path,
      String label,
      String value,
      FieldKind fieldKind
  ) {
    String text = (String.join(" ", path) + " " + label + " " + value).toLowerCase(Locale.ROOT);
    if (isEmailText(text)) {
      return FieldCropper.CropKind.EMAIL;
    }
    if (text.contains("signature")) {
      return FieldCropper.CropKind.SIGNATURE;
    }
    if (fieldKind == FieldKind.SYMBOL_SENSITIVE) {
      return FieldCropper.CropKind.SYMBOL;
    }
    return FieldCropper.CropKind.LONG_TEXT;
  }

  private boolean hasContractNumberValue(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return false;
    }
    if (node.isObject()) {
      var fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        String key = entry.getKey().toLowerCase(Locale.ROOT);
        if ((key.contains("contract") || key.contains("previous_contract"))
            && (key.contains("no") || key.contains("number"))
            && hasApplicantLeafValue(entry.getValue())) {
          return true;
        }
        if (!entry.getKey().startsWith("_") && hasContractNumberValue(entry.getValue())) {
          return true;
        }
      }
      return false;
    }
    if (node.isArray()) {
      for (JsonNode item : node) {
        if (hasContractNumberValue(item)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean hasApplicantLeafValue(JsonNode value) {
    if (value == null || value.isMissingNode() || value.isNull()) {
      return false;
    }
    return !value.isTextual() || !value.asText("").trim().isBlank();
  }

  private void putNormalizedBbox(ObjectNode node, String key, List<Integer> bbox, int imageWidth, int imageHeight) {
    if (bbox.size() < 4 || imageWidth <= 0 || imageHeight <= 0) {
      return;
    }
    ObjectNode value = node.putObject(key);
    value.put("x", round3(bbox.get(0) / (double) imageWidth));
    value.put("y", round3(bbox.get(1) / (double) imageHeight));
    value.put("width", round3((bbox.get(2) - bbox.get(0)) / (double) imageWidth));
    value.put("height", round3((bbox.get(3) - bbox.get(1)) / (double) imageHeight));
  }

  private double round3(double value) {
    return Math.round(value * 1000.0) / 1000.0;
  }

  private FieldCropper.CropResult crop(RenderedOcrPage page, List<Integer> bbox, FieldCropper.CropKind kind) {
    return FieldCropper.crop(page, bbox, kind);
  }

  private void setValue(ObjectNode root, String pageKey, List<String> path, String value) {
    ObjectNode current = objectChild(root, pageKey);
    for (int index = 0; index < path.size() - 1; index += 1) {
      current = objectChild(current, path.get(index));
    }
    if (!path.isEmpty()) {
      current.put(path.get(path.size() - 1), value);
    }
  }

  private String currentTextValue(ObjectNode root, String pageKey, List<String> path, String fallback) {
    JsonNode current = root.path(pageKey);
    for (String part : path) {
      current = current.path(part);
      if (current.isMissingNode()) {
        return fallback == null ? "" : fallback;
      }
    }
    if (current.isTextual() || current.isNumber()) {
      return current.asText("");
    }
    return fallback == null ? "" : fallback;
  }

  private void setNullValue(ObjectNode root, String pageKey, List<String> path) {
    ObjectNode current = objectChild(root, pageKey);
    for (int index = 0; index < path.size() - 1; index += 1) {
      current = objectChild(current, path.get(index));
    }
    if (!path.isEmpty()) {
      current.putNull(path.get(path.size() - 1));
    }
  }

  private void setConfidence(ObjectNode root, String pageKey, List<String> path, double confidence) {
    ObjectNode confidenceRoot = objectChild(root, "_confidence");
    ObjectNode pageConfidence = objectChild(confidenceRoot, pageKey);
    for (int index = 0; index < path.size() - 1; index += 1) {
      pageConfidence = objectChild(pageConfidence, path.get(index));
    }
    if (!path.isEmpty()) {
      pageConfidence.put(path.get(path.size() - 1), Math.round(confidence));
    }
  }

  private void setReviewEvidence(
      ObjectNode root,
      String pageKey,
      List<String> path,
      JsonNode existingEvidence,
      FieldCropTranscriptionResult result,
      GeneralCandidate candidate,
      String filteredValue,
      boolean filteredOut,
      boolean replacementApplied
  ) {
    ObjectNode evidence = existingEvidence instanceof ObjectNode objectNode
        ? objectNode
        : evidenceNode(root, pageKey, path);
    evidence.put("field_crop_review_type", candidate.kind().value());
    evidence.put("field_crop_text", result.text());
    if (hasExcludedMarks(result)) {
      evidence.put("field_crop_text_after_excluded_marks", result.textWithoutExcludedMarks());
    }
    evidence.put("field_crop_confidence", Math.round(result.confidence()));
    evidence.put("field_crop_status", result.status());
    evidence.put("field_crop_original_value", candidate.currentValue());
    evidence.put("field_crop_filtered_out", filteredOut);
    evidence.put("field_crop_replacement_applied", replacementApplied);
    if (filteredOut || filteredValue == null || filteredValue.isBlank()) {
      evidence.putNull("field_crop_filtered_value");
    } else {
      evidence.put("field_crop_filtered_value", filteredValue);
    }
    if (hasExcludedMarks(result)) {
      evidence.set("secondary_excluded_marks", result.excludedMarks().deepCopy());
      mergeExcludedMarks(evidence, result.excludedMarks());
    }
  }

  private void setEmailDomainReviewEvidence(
      ObjectNode root,
      String pageKey,
      List<String> path,
      JsonNode existingEvidence,
      FieldCropTranscriptionResult result,
      String originalValue,
      String filteredValue,
      boolean replacementApplied
  ) {
    ObjectNode evidence = existingEvidence instanceof ObjectNode objectNode
        ? objectNode
        : evidenceNode(root, pageKey, path);
    evidence.put("email_domain_crop_text", result.text());
    evidence.put("email_domain_crop_confidence", Math.round(result.confidence()));
    evidence.put("email_domain_crop_status", result.status());
    evidence.put("email_domain_crop_original_value", originalValue);
    evidence.put("email_domain_crop_replacement_applied", replacementApplied);
    if (filteredValue == null || filteredValue.isBlank()) {
      evidence.putNull("email_domain_crop_filtered_value");
    } else {
      evidence.put("email_domain_crop_filtered_value", filteredValue);
    }
  }

  private void setEmailHyphenPrefixReviewEvidence(
      ObjectNode root,
      String pageKey,
      List<String> path,
      JsonNode existingEvidence,
      FieldCropTranscriptionResult result,
      String originalValue,
      String filteredValue,
      boolean replacementApplied
  ) {
    ObjectNode evidence = existingEvidence instanceof ObjectNode objectNode
        ? objectNode
        : evidenceNode(root, pageKey, path);
    evidence.put("email_hyphen_prefix_crop_text", result.text());
    evidence.put("email_hyphen_prefix_crop_confidence", Math.round(result.confidence()));
    evidence.put("email_hyphen_prefix_crop_status", result.status());
    evidence.put("email_hyphen_prefix_crop_original_value", originalValue);
    evidence.put("email_hyphen_prefix_crop_replacement_applied", replacementApplied);
    if (filteredValue == null || filteredValue.isBlank()) {
      evidence.putNull("email_hyphen_prefix_crop_filtered_value");
    } else {
      evidence.put("email_hyphen_prefix_crop_filtered_value", filteredValue);
    }
  }

  private void mergeExcludedMarks(ObjectNode evidence, JsonNode excludedMarks) {
    JsonNode existing = evidence.path("excluded_marks");
    if (!existing.isArray() || existing.isEmpty()) {
      evidence.set("excluded_marks", excludedMarks.deepCopy());
      return;
    }
    ArrayNode merged = objectMapper.createArrayNode();
    existing.forEach(item -> merged.add(item.deepCopy()));
    excludedMarks.forEach(item -> merged.add(item.deepCopy()));
    evidence.set("excluded_marks", merged);
  }

  private ObjectNode evidenceNode(ObjectNode root, String pageKey, List<String> path) {
    ObjectNode evidenceRoot = objectChild(root, "_field_evidence");
    ObjectNode pageEvidence = objectChild(evidenceRoot, pageKey);
    ObjectNode evidence = pageEvidence;
    for (String part : path) {
      evidence = objectChild(evidence, part);
    }
    return evidence;
  }

  private ObjectNode objectChild(ObjectNode parent, String fieldName) {
    JsonNode existing = parent.get(fieldName);
    if (existing instanceof ObjectNode objectNode) {
      return objectNode;
    }
    ObjectNode child = objectMapper.createObjectNode();
    parent.set(fieldName, child);
    return child;
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

  private boolean isLeafValue(JsonNode node) {
    return node.isNull() || node.isTextual() || node.isBoolean() || node.isNumber();
  }

  private boolean isMetadataLeaf(JsonNode node) {
    return node != null
        && node.isObject()
        && (node.has("value") || node.has("text"))
        && (node.has("confidence") || node.size() <= 4);
  }

  private boolean isControlField(String key) {
    String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT).replaceAll("[_\\s-]+", "");
    return "noapplicantinput".equals(normalized);
  }

  private String normalizeStatus(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
  }

  private String humanize(String key) {
    return key.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
        .replace('_', ' ')
        .replace('-', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }

  private List<String> append(List<String> path, String key) {
    List<String> next = new ArrayList<>(path);
    next.add(key);
    return List.copyOf(next);
  }

  private String key(int page, String path) {
    return page + ":" + path;
  }

  private enum FieldKind {
    SKIP("skip"),
    SYMBOL_SENSITIVE("symbol_sensitive"),
    TEXT("text");

    private final String value;

    FieldKind(String value) {
      this.value = value;
    }

    private String value() {
      return value;
    }
  }

  private record FieldCandidate(List<String> path, JsonNode value) {}

  private record GeneralCandidate(
      int page,
      List<String> path,
      String dottedPath,
      String label,
      String currentValue,
      JsonNode evidence,
      FieldKind kind
  ) {}

  private record EmailParts(String local, String domain) {}
}
