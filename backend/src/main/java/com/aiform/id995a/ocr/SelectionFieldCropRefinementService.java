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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

@Service
public class SelectionFieldCropRefinementService {

  private static final double MIN_BLANK_CONFIDENCE = 70;
  private static final double MIN_REPLACEMENT_CONFIDENCE = 85;
  private static final double APPLICATION_TYPE_CONFIDENCE = 95;
  private static final String ID_988A_TEMPLATE_ID = "id988a_2024_06";
  private static final String APPLICATION_TYPE_ROOT = "application_type";
  private static final String ENTRY_TO_HK_KEY = "entry_to_hong_kong_to_take_up_employment_as_a_domestic_helper_from_abroad";
  private static final String CONTRACT_RENEWAL_KEY = "contract_renewal_with_the_same_employer_or_change_of_employer";
  private static final String REMAINING_CONTRACT_KEY = "complete_the_remaining_extended_period_of_the_current_contract";
  private static final String ENTRY_TO_HK_LABEL = "Entry to Hong Kong to take up employment as a domestic helper from abroad";
  private static final String CONTRACT_RENEWAL_LABEL = "Contract renewal with the same employer or change of employer";
  private static final String REMAINING_CONTRACT_LABEL = "Complete the remaining/extended period of the current contract";
  private static final String ENTRY_VISA_VALUE = "entry visa";
  private static final String ENTRY_VISA_AND_EXTENSION_VALUE = "entry visa AND Extension of Stay";
  private static final String EXTENSION_OF_STAY_VALUE = "Extension of Stay";
  private static final List<ApplicationTypeOption> APPLICATION_TYPE_OPTIONS = List.of(
      new ApplicationTypeOption(ApplicationTypeSlot.ENTRY_TO_HK_ENTRY_VISA, ENTRY_TO_HK_KEY, ENTRY_TO_HK_LABEL, ENTRY_VISA_VALUE),
      new ApplicationTypeOption(ApplicationTypeSlot.CONTRACT_RENEWAL_ENTRY_VISA, CONTRACT_RENEWAL_KEY, CONTRACT_RENEWAL_LABEL, ENTRY_VISA_VALUE),
      new ApplicationTypeOption(
          ApplicationTypeSlot.CONTRACT_RENEWAL_ENTRY_VISA_AND_EXTENSION,
          CONTRACT_RENEWAL_KEY,
          CONTRACT_RENEWAL_LABEL,
          ENTRY_VISA_AND_EXTENSION_VALUE
      ),
      new ApplicationTypeOption(
          ApplicationTypeSlot.REMAINING_CONTRACT_EXTENSION,
          REMAINING_CONTRACT_KEY,
          REMAINING_CONTRACT_LABEL,
          EXTENSION_OF_STAY_VALUE
      )
  );

  private final FieldCropTranscriptionGateway transcriptionGateway;
  private final ObjectMapper objectMapper;

  public SelectionFieldCropRefinementService(
      FieldCropTranscriptionGateway transcriptionGateway,
      ObjectMapper objectMapper
  ) {
    this.transcriptionGateway = transcriptionGateway;
    this.objectMapper = objectMapper;
  }

  public SelectionFieldCropRefinementResult refine(
      String filename,
      JsonNode structuredData,
      List<RenderedOcrPage> pages,
      LlmModelProfile modelProfile
  ) throws IOException {
    return refine(filename, structuredData, pages, modelProfile, null);
  }

  public SelectionFieldCropRefinementResult refine(
      String filename,
      JsonNode structuredData,
      List<RenderedOcrPage> pages,
      LlmModelProfile modelProfile,
      DocumentTemplate template
  ) throws IOException {
    ObjectNode mutableData = structuredData != null && structuredData.isObject()
        ? structuredData.deepCopy()
        : objectMapper.createObjectNode();
    List<FieldCropTranscriptionRequest> requests = new ArrayList<>();
    Map<String, SelectionCandidate> candidatesByKey = new LinkedHashMap<>();

    for (RenderedOcrPage page : pages == null ? List.<RenderedOcrPage>of() : pages) {
      String pageKey = "page_" + page.page();
      JsonNode pageData = mutableData.path(pageKey);
      JsonNode evidenceData = metadataPage(mutableData, "_field_evidence", pageKey);
      List<FieldCandidate> fieldCandidates = new ArrayList<>();
      collectCandidates(pageData, List.of(), fieldCandidates);
      for (FieldCandidate candidate : fieldCandidates) {
        String value = valueText(candidate.value());
        JsonNode evidence = lookupMetadata(evidenceData, candidate.path(), pageKey);
        String label = label(evidence, candidate.path());
        if (!isSelectionCandidate(candidate.path(), label, value)) {
          continue;
        }
        List<Integer> bbox = parseBbox(evidence, page.imageWidth(), page.imageHeight());
        CropResult crop = crop(page, bbox);
        if (crop.bytes().length == 0 || crop.dataUrl().isBlank()) {
          continue;
        }
        String path = String.join(".", candidate.path());
        SelectionCandidate selectionCandidate = new SelectionCandidate(page.page(), candidate.path(), path, label, value, evidence);
        candidatesByKey.put(key(page.page(), path), selectionCandidate);
        requests.add(new FieldCropTranscriptionRequest(
            page.page(),
            path,
            label,
            value,
            crop.bytes(),
            crop.dataUrl()
        ));
      }
      addMissingSeparateServantRoomCandidate(page, pageData, mutableData, requests, candidatesByKey);
      addMissingHouseholdIncomeDeclarationCandidate(page, pageData, mutableData, requests, candidatesByKey);
      addMissingHkIdentityCardNoCandidate(page, pageData, mutableData, requests, candidatesByKey);
    }

    int updated = shouldApplyId988aRules(template) ? restoreApplicationTypeSelections(mutableData, pages) : 0;
    if (requests.isEmpty()) {
      return new SelectionFieldCropRefinementResult(mutableData, 0, updated);
    }

    List<FieldCropTranscriptionResult> results;
    try {
      results = transcriptionGateway.transcribeFieldCrops(filename, requests, modelProfile);
    } catch (IOException exception) {
      return new SelectionFieldCropRefinementResult(mutableData, requests.size(), 0);
    }

    for (FieldCropTranscriptionResult result : results == null ? List.<FieldCropTranscriptionResult>of() : results) {
      SelectionCandidate candidate = candidatesByKey.get(key(result.page(), result.path()));
      if (candidate == null) {
        continue;
      }
      String filteredCropText = result.textWithoutExcludedMarks();
      if (shouldClear(result, filteredCropText)) {
        removeValue(mutableData, "page_" + candidate.page(), candidate.path());
        setConfidence(mutableData, "page_" + candidate.page(), candidate.path(), result.confidence());
        setSelectionEvidence(mutableData, "page_" + candidate.page(), candidate.path(), candidate.evidence(), result, candidate.currentValue(), "");
        updated += 1;
        continue;
      }
      if (shouldReplace(candidate.currentValue(), result, filteredCropText)) {
        String nextValue = filteredCropText;
        setValue(mutableData, "page_" + candidate.page(), candidate.path(), nextValue);
        setConfidence(mutableData, "page_" + candidate.page(), candidate.path(), result.confidence());
        setSelectionEvidence(mutableData, "page_" + candidate.page(), candidate.path(), candidate.evidence(), result, candidate.currentValue(), nextValue);
        updated += 1;
      }
    }

    return new SelectionFieldCropRefinementResult(mutableData, requests.size(), updated);
  }

  private int restoreApplicationTypeSelections(ObjectNode mutableData, List<RenderedOcrPage> pages) {
    RenderedOcrPage firstPage = firstPage(pages);
    if (firstPage == null) {
      return 0;
    }
    String pageKey = "page_" + firstPage.page();
    ObjectNode pageData = objectChild(mutableData, pageKey);
    boolean hadFirstPassApplicationType = hasFilledValue(pageData, List.of(APPLICATION_TYPE_ROOT));
    List<ApplicationTypeSelection> selected = selectedApplicationTypeCheckboxes(firstPage);
    if (selected.isEmpty()) {
      int updated = clearGenericApplicationTypeFields(pageData);
      if (hadFirstPassApplicationType) {
        pageData.remove(APPLICATION_TYPE_ROOT);
        updated += 1;
      }
      return updated;
    }
    int updated = clearGenericApplicationTypeFields(pageData);
    Map<String, List<ApplicationTypeSelection>> selectedByField = new LinkedHashMap<>();
    for (ApplicationTypeSelection selection : selected) {
      selectedByField.computeIfAbsent(selection.option().fieldKey(), ignored -> new ArrayList<>()).add(selection);
    }

    ObjectNode applicationType = objectChild(pageData, APPLICATION_TYPE_ROOT);
    applicationType.removeAll();
    for (String fieldKey : List.of(ENTRY_TO_HK_KEY, CONTRACT_RENEWAL_KEY, REMAINING_CONTRACT_KEY)) {
      List<ApplicationTypeSelection> selections = selectedByField.getOrDefault(fieldKey, List.of());
      if (selections.isEmpty()) {
        continue;
      }
      String value = joinedSelectedValues(selections);
      String label = selections.get(0).option().label();
      List<Integer> bbox = unionBbox(selections.stream()
          .map(ApplicationTypeSelection::bbox)
          .toList());
      applicationType.put(fieldKey, value);
      setConfidence(mutableData, pageKey, List.of(APPLICATION_TYPE_ROOT, fieldKey), APPLICATION_TYPE_CONFIDENCE);
      ObjectNode evidence = evidenceNode(mutableData, pageKey, List.of(APPLICATION_TYPE_ROOT, fieldKey));
      evidence.put("label", label);
      evidence.put("selection_crop_status", "detected");
      evidence.put("selection_crop_confidence", Math.round(APPLICATION_TYPE_CONFIDENCE));
      evidence.put("selection_crop_text", value);
      putNormalizedBbox(evidence, "value_bbox", bbox, firstPage.imageWidth(), firstPage.imageHeight());
      updated += 1;
    }
    return updated;
  }

  private int clearGenericApplicationTypeFields(ObjectNode pageData) {
    int updated = 0;
    for (String field : List.of("visa_type", "type_of_application")) {
      if (pageData.has(field)) {
        pageData.remove(field);
        updated += 1;
      }
    }
    return updated;
  }

  private RenderedOcrPage firstPage(List<RenderedOcrPage> pages) {
    if (pages == null) {
      return null;
    }
    return pages.stream()
        .filter(page -> page != null && page.page() == 1)
        .findFirst()
        .orElse(null);
  }

  private List<ApplicationTypeSelection> selectedApplicationTypeCheckboxes(RenderedOcrPage page) {
    if (page.pngBytes() == null || page.pngBytes().length == 0) {
      return List.of();
    }
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(page.pngBytes()));
      if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
        return List.of();
      }
      ApplicationTypeTable table = detectApplicationTypeTable(image);
      if (table == null) {
        return List.of();
      }
      return detectApplicationTypeSelections(image, table);
    } catch (IOException | RuntimeException exception) {
      return List.of();
    }
  }

  private boolean shouldApplyId988aRules(DocumentTemplate template) {
    return template != null && template.isTemplate(ID_988A_TEMPLATE_ID);
  }

  private ApplicationTypeTable detectApplicationTypeTable(BufferedImage image) {
    List<HorizontalLine> horizontalLines = detectWideHorizontalLines(image);
    for (int index = 0; index <= horizontalLines.size() - 5; index += 1) {
      List<HorizontalLine> tableLines = horizontalLines.subList(index, index + 5);
      int top = tableLines.get(0).y();
      int bottom = tableLines.get(4).y();
      int height = bottom - top;
      if (height < Math.round(image.getHeight() * 0.08f) || height > Math.round(image.getHeight() * 0.42f)) {
        continue;
      }
      int left = tableLines.stream().mapToInt(HorizontalLine::left).min().orElse(0);
      int right = tableLines.stream().mapToInt(HorizontalLine::right).max().orElse(0);
      int width = right - left;
      if (width < Math.round(image.getWidth() * 0.45f)) {
        continue;
      }
      int separator = findApplicationTypeOptionSeparator(image, left, tableLines.get(1).y(), right, bottom);
      if (separator < 0) {
        continue;
      }
      ApplicationTypeTable table = new ApplicationTypeTable(left, top, right, bottom, separator, List.copyOf(tableLines));
      if (applicationTypeBoxesBySlot(image, table).size() >= APPLICATION_TYPE_OPTIONS.size()) {
        return table;
      }
    }
    return null;
  }

  private List<ApplicationTypeSelection> detectApplicationTypeSelections(BufferedImage image, ApplicationTypeTable table) {
    Map<ApplicationTypeSlot, CheckboxBox> boxesBySlot = applicationTypeBoxesBySlot(image, table);
    List<ApplicationTypeSelection> selections = new ArrayList<>();
    for (ApplicationTypeOption option : APPLICATION_TYPE_OPTIONS) {
      CheckboxBox box = boxesBySlot.get(option.slot());
      if (box == null) {
        continue;
      }
      List<Integer> bbox = expandedCheckboxBbox(box, image.getWidth(), image.getHeight());
      if (hasIntentionalCheckboxMarkNearCheckboxBox(image, box)) {
        selections.add(new ApplicationTypeSelection(option, bbox));
      }
    }
    return List.copyOf(selections);
  }

  private Map<ApplicationTypeSlot, CheckboxBox> applicationTypeBoxesBySlot(BufferedImage image, ApplicationTypeTable table) {
    List<CheckboxBox> boxes = completeApplicationTypeCheckboxBoxes(image, table, detectCheckboxBoxes(image, table));
    if (boxes.size() < APPLICATION_TYPE_OPTIONS.size()) {
      return Map.of();
    }
    Map<ApplicationTypeSlot, CheckboxBox> boxesBySlot = new LinkedHashMap<>();
    List<CheckboxSearchBand> bands = applicationTypeSearchBands(table);
    putBoxForBand(boxesBySlot, ApplicationTypeSlot.ENTRY_TO_HK_ENTRY_VISA, boxes, bands.get(0));
    putBoxForBand(boxesBySlot, ApplicationTypeSlot.CONTRACT_RENEWAL_ENTRY_VISA, boxes, bands.get(1));
    putBoxForBand(boxesBySlot, ApplicationTypeSlot.CONTRACT_RENEWAL_ENTRY_VISA_AND_EXTENSION, boxes, bands.get(2));
    putBoxForBand(boxesBySlot, ApplicationTypeSlot.REMAINING_CONTRACT_EXTENSION, boxes, bands.get(3));
    if (boxesBySlot.size() < APPLICATION_TYPE_OPTIONS.size()) {
      List<CheckboxBox> ordered = boxes.stream()
          .sorted(Comparator.comparingInt(CheckboxBox::centerY).thenComparingInt(CheckboxBox::centerX))
          .limit(APPLICATION_TYPE_OPTIONS.size())
          .toList();
      boxesBySlot.putIfAbsent(ApplicationTypeSlot.ENTRY_TO_HK_ENTRY_VISA, ordered.get(0));
      boxesBySlot.putIfAbsent(ApplicationTypeSlot.CONTRACT_RENEWAL_ENTRY_VISA, ordered.get(1));
      boxesBySlot.putIfAbsent(ApplicationTypeSlot.CONTRACT_RENEWAL_ENTRY_VISA_AND_EXTENSION, ordered.get(2));
      boxesBySlot.putIfAbsent(ApplicationTypeSlot.REMAINING_CONTRACT_EXTENSION, ordered.get(3));
    }
    return boxesBySlot;
  }

  private void putBoxForBand(
      Map<ApplicationTypeSlot, CheckboxBox> boxesBySlot,
      ApplicationTypeSlot slot,
      List<CheckboxBox> boxes,
      CheckboxSearchBand band
  ) {
    boxes.stream()
        .filter(box -> box.centerY() >= band.top() && box.centerY() < band.bottom())
        .min(Comparator.comparingInt(CheckboxBox::centerX))
        .ifPresent(box -> boxesBySlot.put(slot, box));
  }

  private List<CheckboxBox> completeApplicationTypeCheckboxBoxes(
      BufferedImage image,
      ApplicationTypeTable table,
      List<CheckboxBox> detectedBoxes
  ) {
    if (detectedBoxes.size() >= APPLICATION_TYPE_OPTIONS.size() || detectedBoxes.isEmpty()) {
      return detectedBoxes;
    }
    int centerX = Math.round((float) detectedBoxes.stream().mapToInt(CheckboxBox::centerX).average().orElse(0));
    int side = Math.round((float) detectedBoxes.stream()
        .mapToInt(box -> Math.max(box.width(), box.height()))
        .average()
        .orElse(Math.max(8, (table.bottom() - table.top()) * 0.08)));
    List<CheckboxSearchBand> bands = applicationTypeSearchBands(table);
    List<CheckboxBox> completed = new ArrayList<>(detectedBoxes);
    for (CheckboxSearchBand band : bands) {
      boolean alreadyDetected = completed.stream()
          .anyMatch(box -> box.centerY() >= band.top() && box.centerY() < band.bottom());
      if (alreadyDetected) {
        continue;
      }
      CheckboxBox recovered = scanCheckboxOutlineNearColumn(image, table, centerX, side, band);
      if (recovered != null) {
        completed.add(recovered);
      }
    }
    return mergeNearbyCheckboxBoxes(completed).stream()
        .sorted(Comparator.comparingInt(CheckboxBox::centerY).thenComparingInt(CheckboxBox::centerX))
        .toList();
  }

  private List<CheckboxSearchBand> applicationTypeSearchBands(ApplicationTypeTable table) {
    int headerBottom = table.lines().get(1).y();
    int entryBottom = table.lines().get(2).y();
    int contractBottom = table.lines().get(3).y();
    int remainingBottom = table.lines().get(4).y();
    return List.of(
        new CheckboxSearchBand(headerBottom, entryBottom),
        new CheckboxSearchBand(entryBottom, (entryBottom + contractBottom) / 2),
        new CheckboxSearchBand((entryBottom + contractBottom) / 2, contractBottom),
        new CheckboxSearchBand(contractBottom, remainingBottom)
    );
  }

  private CheckboxBox scanCheckboxOutlineNearColumn(
      BufferedImage image,
      ApplicationTypeTable table,
      int centerX,
      int expectedSide,
      CheckboxSearchBand band
  ) {
    int tableHeight = table.bottom() - table.top();
    int minSide = Math.max(14, Math.round(tableHeight * 0.035f));
    int maxSide = Math.max(minSide + 1, Math.round(tableHeight * 0.18f));
    int searchLeft = Math.max(table.separator(), centerX - expectedSide);
    int searchRight = Math.min(table.right(), centerX + expectedSide);
    ScoredCheckboxBox best = null;
    for (int side = minSide; side <= maxSide; side += 2) {
      for (int y = band.top(); y <= band.bottom() - side; y += 2) {
        for (int x = searchLeft; x <= searchRight - side; x += 2) {
          double outlineScore = checkboxOutlineScore(image, x, y, x + side, y + side);
          if (outlineScore < 0.58) {
            continue;
          }
          double fill = printedInkFill(image, x, y, x + side, y + side);
          if (fill < 0.08 || fill > 0.55) {
            continue;
          }
          if (best == null || outlineScore > best.score()) {
            best = new ScoredCheckboxBox(new CheckboxBox(x, y, x + side, y + side), outlineScore);
          }
        }
      }
    }
    return best == null ? null : best.box();
  }

  private List<HorizontalLine> detectWideHorizontalLines(BufferedImage image) {
    List<HorizontalLine> candidates = new ArrayList<>();
    int minimumInkColumns = Math.max(1, Math.round(image.getWidth() * 0.45f));
    HorizontalLine bestInGroup = null;
    int previousY = -10;
    for (int y = 0; y < image.getHeight(); y += 1) {
      HorizontalLine line = horizontalLineAt(image, y, minimumInkColumns);
      if (line == null) {
        if (bestInGroup != null) {
          candidates.add(bestInGroup);
          bestInGroup = null;
        }
        previousY = -10;
        continue;
      }
      if (bestInGroup == null || y - previousY > 2) {
        if (bestInGroup != null) {
          candidates.add(bestInGroup);
        }
        bestInGroup = line;
      } else if (line.coverage() > bestInGroup.coverage()) {
        bestInGroup = line;
      }
      previousY = y;
    }
    if (bestInGroup != null) {
      candidates.add(bestInGroup);
    }
    return candidates.stream()
        .sorted(Comparator.comparingInt(HorizontalLine::y))
        .toList();
  }

  private HorizontalLine horizontalLineAt(BufferedImage image, int y, int minimumInkColumns) {
    int band = Math.max(1, Math.round(image.getHeight() * 0.0012f));
    int first = -1;
    int last = -1;
    int inkColumns = 0;
    for (int x = 0; x < image.getWidth(); x += 1) {
      boolean found = false;
      for (int yy = Math.max(0, y - band); yy <= Math.min(image.getHeight() - 1, y + band); yy += 1) {
        if (isPrintedDarkInk(image.getRGB(x, yy))) {
          found = true;
          break;
        }
      }
      if (found) {
        if (first < 0) {
          first = x;
        }
        last = x;
        inkColumns += 1;
      }
    }
    if (first < 0 || inkColumns < minimumInkColumns) {
      return null;
    }
    int span = Math.max(1, last - first + 1);
    double coverage = inkColumns / (double) span;
    if (span < Math.round(image.getWidth() * 0.50f) || coverage < 0.68) {
      return null;
    }
    return new HorizontalLine(y, first, last, coverage);
  }

  private int findApplicationTypeOptionSeparator(BufferedImage image, int left, int top, int right, int bottom) {
    int width = right - left;
    int searchLeft = left + Math.round(width * 0.45f);
    int searchRight = right - Math.max(3, Math.round(width * 0.08f));
    int bestX = -1;
    double bestCoverage = 0;
    for (int x = searchLeft; x < searchRight; x += 1) {
      double coverage = verticalLineCoverageAt(image, x, top, bottom);
      if (coverage > bestCoverage) {
        bestCoverage = coverage;
        bestX = x;
      }
    }
    return bestCoverage >= 0.45 ? bestX : -1;
  }

  private double verticalLineCoverageAt(BufferedImage image, int x, int top, int bottom) {
    int band = Math.max(1, Math.round(image.getWidth() * 0.0012f));
    int rowsWithInk = 0;
    for (int y = Math.max(0, top); y < Math.min(image.getHeight(), bottom); y += 1) {
      boolean found = false;
      for (int xx = Math.max(0, x - band); xx <= Math.min(image.getWidth() - 1, x + band); xx += 1) {
        if (isPrintedDarkInk(image.getRGB(xx, y))) {
          found = true;
          break;
        }
      }
      if (found) {
        rowsWithInk += 1;
      }
    }
    return rowsWithInk / (double) Math.max(1, bottom - top);
  }

  private List<CheckboxBox> detectCheckboxBoxes(BufferedImage image, ApplicationTypeTable table) {
    int tableWidth = table.right() - table.left();
    int optionLeft = table.separator() + Math.max(2, Math.round(tableWidth * 0.01f));
    int optionRight = table.right() - Math.max(2, Math.round(tableWidth * 0.01f));
    int optionTop = table.lines().get(1).y();
    int optionBottom = table.bottom();
    int roiWidth = Math.max(1, optionRight - optionLeft);
    int roiHeight = Math.max(1, optionBottom - optionTop);
    boolean[] visited = new boolean[roiWidth * roiHeight];
    List<CheckboxBox> boxes = new ArrayList<>();

    for (int localY = 0; localY < roiHeight; localY += 1) {
      for (int localX = 0; localX < roiWidth; localX += 1) {
        int index = localY * roiWidth + localX;
        if (visited[index]) {
          continue;
        }
        visited[index] = true;
        int x = optionLeft + localX;
        int y = optionTop + localY;
        if (!isPrintedDarkInk(image.getRGB(x, y))) {
          continue;
        }
        CheckboxComponent component = collectDarkComponent(image, visited, roiWidth, roiHeight, optionLeft, optionTop, localX, localY);
        CheckboxBox box = checkboxBoxFromComponent(image, component, table, optionLeft, optionRight);
        if (box != null) {
          boxes.add(box);
        }
      }
    }

    return keepLeftmostCheckboxColumn(mergeNearbyCheckboxBoxes(boxes), optionLeft, optionRight).stream()
        .sorted(Comparator.comparingInt(CheckboxBox::centerY).thenComparingInt(CheckboxBox::centerX))
        .toList();
  }

  private double printedInkFill(BufferedImage image, int left, int top, int right, int bottom) {
    int ink = 0;
    int total = 0;
    for (int y = Math.max(0, top); y < Math.min(image.getHeight(), bottom); y += 1) {
      for (int x = Math.max(0, left); x < Math.min(image.getWidth(), right); x += 1) {
        if (isPrintedDarkInk(image.getRGB(x, y))) {
          ink += 1;
        }
        total += 1;
      }
    }
    return ink / (double) Math.max(1, total);
  }

  private CheckboxComponent collectDarkComponent(
      BufferedImage image,
      boolean[] visited,
      int roiWidth,
      int roiHeight,
      int optionLeft,
      int optionTop,
      int startX,
      int startY
  ) {
    ArrayDeque<int[]> queue = new ArrayDeque<>();
    queue.add(new int[] {startX, startY});
    int left = optionLeft + startX;
    int right = left + 1;
    int top = optionTop + startY;
    int bottom = top + 1;
    int pixels = 0;
    while (!queue.isEmpty()) {
      int[] point = queue.removeFirst();
      int localX = point[0];
      int localY = point[1];
      int x = optionLeft + localX;
      int y = optionTop + localY;
      pixels += 1;
      left = Math.min(left, x);
      right = Math.max(right, x + 1);
      top = Math.min(top, y);
      bottom = Math.max(bottom, y + 1);
      int[][] neighbors = {
          {localX - 1, localY},
          {localX + 1, localY},
          {localX, localY - 1},
          {localX, localY + 1}
      };
      for (int[] neighbor : neighbors) {
        int nx = neighbor[0];
        int ny = neighbor[1];
        if (nx < 0 || ny < 0 || nx >= roiWidth || ny >= roiHeight) {
          continue;
        }
        int index = ny * roiWidth + nx;
        if (visited[index]) {
          continue;
        }
        visited[index] = true;
        if (isPrintedDarkInk(image.getRGB(optionLeft + nx, optionTop + ny))) {
          queue.addLast(new int[] {nx, ny});
        }
      }
    }
    return new CheckboxComponent(left, top, right, bottom, pixels);
  }

  private boolean looksLikeCheckboxBox(
      BufferedImage image,
      CheckboxComponent component,
      ApplicationTypeTable table,
      int optionLeft,
      int optionRight
  ) {
    int width = component.right() - component.left();
    int height = component.bottom() - component.top();
    int tableHeight = table.bottom() - table.top();
    int minSide = Math.max(14, Math.round(tableHeight * 0.035f));
    int maxSide = Math.max(minSide + 1, Math.round(tableHeight * 0.18f));
    if (width < minSide || height < minSide || width > maxSide || height > maxSide) {
      return false;
    }
    double aspect = width / (double) Math.max(1, height);
    if (aspect < 0.65 || aspect > 1.45) {
      return false;
    }
    int optionWidth = optionRight - optionLeft;
    if (component.centerX() > optionLeft + Math.round(optionWidth * 0.34f)) {
      return false;
    }
    double fill = component.pixels() / (double) Math.max(1, width * height);
    if (fill < 0.12 || fill > 0.70) {
      return false;
    }
    return checkboxOutlineScore(image, component.left(), component.top(), component.right(), component.bottom()) >= 0.42;
  }

  private CheckboxBox checkboxBoxFromComponent(
      BufferedImage image,
      CheckboxComponent component,
      ApplicationTypeTable table,
      int optionLeft,
      int optionRight
  ) {
    if (looksLikeCheckboxBox(image, component, table, optionLeft, optionRight)) {
      return new CheckboxBox(component.left(), component.top(), component.right(), component.bottom());
    }
    return extractCheckboxBoxFromMarkedComponent(image, component, table, optionLeft, optionRight);
  }

  private CheckboxBox extractCheckboxBoxFromMarkedComponent(
      BufferedImage image,
      CheckboxComponent component,
      ApplicationTypeTable table,
      int optionLeft,
      int optionRight
  ) {
    int tableHeight = table.bottom() - table.top();
    int minSide = Math.max(14, Math.round(tableHeight * 0.035f));
    int maxSide = Math.max(minSide + 1, Math.round(tableHeight * 0.18f));
    int optionWidth = optionRight - optionLeft;
    int componentWidth = component.right() - component.left();
    int componentHeight = component.bottom() - component.top();
    if (component.centerX() > optionLeft + Math.round(optionWidth * 0.34f)
        || componentWidth < minSide
        || componentHeight < minSide
        || componentWidth > maxSide * 2
        || componentHeight > maxSide * 3) {
      return null;
    }
    ScoredCheckboxBox best = null;
    int searchLeft = Math.max(optionLeft, component.left() - maxSide);
    int searchRight = Math.min(optionRight, component.right());
    int searchTop = Math.max(table.lines().get(1).y(), component.top());
    int searchBottom = Math.min(table.bottom(), component.bottom() + maxSide);
    for (int side = minSide; side <= maxSide; side += 2) {
      for (int y = searchTop; y <= searchBottom - side; y += 2) {
        for (int x = searchLeft; x <= searchRight - side; x += 2) {
          double outlineScore = checkboxOutlineScore(image, x, y, x + side, y + side);
          if (outlineScore < 0.58) {
            continue;
          }
          double fill = printedInkFill(image, x, y, x + side, y + side);
          if (fill < 0.08 || fill > 0.55) {
            continue;
          }
          if (best == null || outlineScore > best.score()) {
            best = new ScoredCheckboxBox(new CheckboxBox(x, y, x + side, y + side), outlineScore);
          }
        }
      }
    }
    return best == null ? null : best.box();
  }

  private double checkboxOutlineScore(BufferedImage image, int left, int top, int right, int bottom) {
    int width = right - left;
    int height = bottom - top;
    int band = Math.max(1, Math.round(Math.min(width, height) * 0.18f));
    double topCoverage = horizontalEdgeCoverage(image, left, right, top, top + band);
    double bottomCoverage = horizontalEdgeCoverage(image, left, right, bottom - band, bottom);
    double leftCoverage = verticalEdgeCoverage(image, left, left + band, top, bottom);
    double rightCoverage = verticalEdgeCoverage(image, right - band, right, top, bottom);
    return (topCoverage + bottomCoverage + leftCoverage + rightCoverage) / 4.0;
  }

  private double horizontalEdgeCoverage(BufferedImage image, int left, int right, int top, int bottom) {
    int covered = 0;
    for (int x = Math.max(0, left); x < Math.min(image.getWidth(), right); x += 1) {
      boolean found = false;
      for (int y = Math.max(0, top); y < Math.min(image.getHeight(), bottom); y += 1) {
        if (isPrintedDarkInk(image.getRGB(x, y))) {
          found = true;
          break;
        }
      }
      if (found) {
        covered += 1;
      }
    }
    return covered / (double) Math.max(1, right - left);
  }

  private double verticalEdgeCoverage(BufferedImage image, int left, int right, int top, int bottom) {
    int covered = 0;
    for (int y = Math.max(0, top); y < Math.min(image.getHeight(), bottom); y += 1) {
      boolean found = false;
      for (int x = Math.max(0, left); x < Math.min(image.getWidth(), right); x += 1) {
        if (isPrintedDarkInk(image.getRGB(x, y))) {
          found = true;
          break;
        }
      }
      if (found) {
        covered += 1;
      }
    }
    return covered / (double) Math.max(1, bottom - top);
  }

  private List<CheckboxBox> mergeNearbyCheckboxBoxes(List<CheckboxBox> boxes) {
    List<CheckboxBox> merged = new ArrayList<>();
    for (CheckboxBox box : boxes.stream().sorted(Comparator.comparingInt(CheckboxBox::centerY)).toList()) {
      CheckboxBox match = null;
      for (CheckboxBox existing : merged) {
        int side = Math.max(Math.max(existing.width(), existing.height()), Math.max(box.width(), box.height()));
        if (Math.abs(existing.centerX() - box.centerX()) <= side && Math.abs(existing.centerY() - box.centerY()) <= side) {
          match = existing;
          break;
        }
      }
      if (match == null) {
        merged.add(box);
      }
    }
    return merged;
  }

  private List<CheckboxBox> keepLeftmostCheckboxColumn(List<CheckboxBox> boxes, int optionLeft, int optionRight) {
    if (boxes.isEmpty()) {
      return List.of();
    }
    int optionWidth = optionRight - optionLeft;
    int leftmostCenter = boxes.stream().mapToInt(CheckboxBox::centerX).min().orElse(optionLeft);
    int tolerance = Math.max(10, Math.round(optionWidth * 0.10f));
    return boxes.stream()
        .filter(box -> box.centerX() <= leftmostCenter + tolerance)
        .toList();
  }

  private List<Integer> expandedCheckboxBbox(CheckboxBox box, int imageWidth, int imageHeight) {
    int side = Math.max(box.width(), box.height());
    int marginX = Math.max(2, Math.round(side * 0.12f));
    int marginY = Math.max(2, Math.round(side * 0.12f));
    return List.of(
        Math.max(0, box.left() - marginX),
        Math.max(0, box.top() - marginY),
        Math.min(imageWidth, box.right() + marginX),
        Math.min(imageHeight, box.bottom() + marginY)
    );
  }

  private boolean isPrintedDarkInk(int rgb) {
    int red = (rgb >> 16) & 0xff;
    int green = (rgb >> 8) & 0xff;
    int blue = rgb & 0xff;
    int average = (red + green + blue) / 3;
    boolean blueApplicantInk = blue >= 90
        && blue > red + 20
        && blue > green + 8
        && (blue - red) + (blue - green) >= 55;
    return average < 155 && !blueApplicantInk;
  }

  private boolean hasIntentionalCheckboxMarkNearCheckboxBox(BufferedImage image, CheckboxBox box) {
    int side = Math.max(box.width(), box.height());
    int left = Math.max(0, box.left());
    int top = Math.max(0, box.top() - Math.round(side * 0.75f));
    int right = Math.min(image.getWidth(), box.right());
    int bottom = Math.min(image.getHeight(), box.bottom());
    int borderBand = Math.max(2, Math.round(side * 0.18f));
    int ink = 0;
    int upperRightInk = 0;
    int lowerLeftInk = 0;
    int upperLeftInk = 0;
    int lowerRightInk = 0;
    int diagonalInk = 0;
    int blueInk = 0;
    int minBlueX = right;
    int minBlueY = bottom;
    int maxBlueX = left;
    int maxBlueY = top;
    int minInkX = right;
    int minInkY = bottom;
    int maxInkX = left;
    int maxInkY = top;
    for (int y = top; y < bottom; y += 1) {
      for (int x = left; x < right; x += 1) {
        if (!isCheckboxMarkInk(image.getRGB(x, y)) || isOnPrintedCheckboxBorder(x, y, box, borderBand)) {
          continue;
        }
        ink += 1;
        if (isBlueInk(image.getRGB(x, y))) {
          blueInk += 1;
          minBlueX = Math.min(minBlueX, x);
          minBlueY = Math.min(minBlueY, y);
          maxBlueX = Math.max(maxBlueX, x);
          maxBlueY = Math.max(maxBlueY, y);
        }
        minInkX = Math.min(minInkX, x);
        minInkY = Math.min(minInkY, y);
        maxInkX = Math.max(maxInkX, x);
        maxInkY = Math.max(maxInkY, y);
        double nx = (x - box.left()) / (double) Math.max(1, side);
        double ny = (y - box.top()) / (double) Math.max(1, side);
        if (nx >= 0.48 && ny <= 0.45) {
          upperRightInk += 1;
        }
        if (nx <= 0.62 && ny >= 0.42) {
          lowerLeftInk += 1;
        }
        if (nx <= 0.40 && ny <= 0.40) {
          upperLeftInk += 1;
        }
        if (nx >= 0.62 && ny >= 0.62) {
          lowerRightInk += 1;
        }
        if (Math.abs(ny - (1.0 - nx)) <= 0.24 || Math.abs(ny - nx) <= 0.24) {
          diagonalInk += 1;
        }
      }
    }
    if (ink < Math.max(8, Math.round(side * 0.45f))) {
      return false;
    }
    int markWidth = maxInkX - minInkX + 1;
    int markHeight = maxInkY - minInkY + 1;
    if (markWidth < Math.round(side * 0.35f) || markHeight < Math.round(side * 0.35f)) {
      return false;
    }
    double localDensity = ink / (double) Math.max(1, markWidth * markHeight);
    if (localDensity > 0.36) {
      return false;
    }
    if (blueInk >= 12) {
      double blueLocalDensity = blueInk / (double) Math.max(1, (maxBlueX - minBlueX + 1) * (maxBlueY - minBlueY + 1));
      if (blueLocalDensity > 0.34) {
        return false;
      }
    }
    if (diagonalInk / (double) Math.max(1, ink) < 0.38) {
      return false;
    }
    int endpointThreshold = Math.max(3, Math.round(side * 0.16f));
    boolean slashShape = upperRightInk >= endpointThreshold && lowerLeftInk >= endpointThreshold;
    boolean backslashShape = upperLeftInk >= endpointThreshold && lowerRightInk >= endpointThreshold;
    return slashShape || backslashShape;
  }

  private boolean isOnPrintedCheckboxBorder(int x, int y, CheckboxBox box, int borderBand) {
    boolean insideBox = x >= box.left() && x < box.right() && y >= box.top() && y < box.bottom();
    if (!insideBox) {
      return false;
    }
    boolean nearVerticalBorder = x < box.left() + borderBand || x >= box.right() - borderBand;
    boolean nearHorizontalBorder = y < box.top() + borderBand || y >= box.bottom() - borderBand;
    return nearVerticalBorder || nearHorizontalBorder;
  }

  private boolean hasIntentionalCheckboxMark(BufferedImage image, List<Integer> bbox) {
    if (bbox.size() < 4) {
      return false;
    }
    int left = Math.max(0, Math.min(image.getWidth(), bbox.get(0)));
    int top = Math.max(0, Math.min(image.getHeight(), bbox.get(1)));
    int right = Math.max(left, Math.min(image.getWidth(), bbox.get(2)));
    int bottom = Math.max(top, Math.min(image.getHeight(), bbox.get(3)));
    int width = right - left;
    int height = bottom - top;
    if (width < 8 || height < 8) {
      return false;
    }
    if (hasBlueApplicantInk(image, left, top, right, bottom)) {
      return true;
    }
    int marginX = Math.max(3, Math.round(width * 0.28f));
    int marginY = Math.max(3, Math.round(height * 0.28f));
    int innerLeft = Math.min(right, left + marginX);
    int innerTop = Math.min(bottom, top + marginY);
    int innerRight = Math.max(innerLeft, right - marginX);
    int innerBottom = Math.max(innerTop, bottom - marginY);
    int innerWidth = innerRight - innerLeft;
    int innerHeight = innerBottom - innerTop;
    if (innerWidth < 4 || innerHeight < 4) {
      return false;
    }

    int ink = 0;
    int diagonalInk = 0;
    int upperRightInk = 0;
    int lowerLeftInk = 0;
    int upperLeftInk = 0;
    int lowerRightInk = 0;
    boolean[] slashColumns = new boolean[innerWidth];
    boolean[] backslashColumns = new boolean[innerWidth];
    int minInkX = innerRight;
    int minInkY = innerBottom;
    int maxInkX = innerLeft;
    int maxInkY = innerTop;
    double diagonalBand = 0.18;
    for (int y = innerTop; y < innerBottom; y += 1) {
      for (int x = innerLeft; x < innerRight; x += 1) {
        if (isCheckboxMarkInk(image.getRGB(x, y))) {
          ink += 1;
          minInkX = Math.min(minInkX, x);
          minInkY = Math.min(minInkY, y);
          maxInkX = Math.max(maxInkX, x);
          maxInkY = Math.max(maxInkY, y);
          double nx = innerWidth <= 1 ? 0 : (x - innerLeft) / (double) (innerWidth - 1);
          double ny = innerHeight <= 1 ? 0 : (y - innerTop) / (double) (innerHeight - 1);
          if (nx >= 0.70 && ny <= 0.32) {
            upperRightInk += 1;
          }
          if (nx <= 0.42 && ny >= 0.60) {
            lowerLeftInk += 1;
          }
          if (nx <= 0.32 && ny <= 0.32) {
            upperLeftInk += 1;
          }
          if (nx >= 0.68 && ny >= 0.68) {
            lowerRightInk += 1;
          }
          if (Math.abs(ny - (1.0 - nx)) <= diagonalBand) {
            slashColumns[x - innerLeft] = true;
            diagonalInk += 1;
          } else if (Math.abs(ny - nx) <= diagonalBand) {
            backslashColumns[x - innerLeft] = true;
            diagonalInk += 1;
          }
        }
      }
    }

    int area = Math.max(1, innerWidth * innerHeight);
    if (ink < Math.max(8, Math.round(area * 0.035f))) {
      return false;
    }
    double density = ink / (double) area;
    if (density > 0.46) {
      return false;
    }
    double localDensity = ink / (double) Math.max(1, (maxInkX - minInkX + 1) * (maxInkY - minInkY + 1));
    if (localDensity > 0.55) {
      return false;
    }
    double xCoverage = (maxInkX - minInkX + 1) / (double) innerWidth;
    double yCoverage = (maxInkY - minInkY + 1) / (double) innerHeight;
    if (xCoverage < 0.55 || yCoverage < 0.55) {
      return false;
    }
    double diagonalRatio = diagonalInk / (double) ink;
    int diagonalColumns = Math.max(markedColumns(slashColumns), markedColumns(backslashColumns));
    boolean slashEndpointShape = upperRightInk >= 6 && lowerLeftInk >= 6;
    boolean backslashEndpointShape = upperLeftInk >= 6 && lowerRightInk >= 6;
    return diagonalRatio >= 0.42
        && diagonalColumns >= Math.max(4, Math.round(innerWidth * 0.55f))
        && (slashEndpointShape || backslashEndpointShape);
  }

  private boolean hasBlueApplicantInk(BufferedImage image, int left, int top, int right, int bottom) {
    int count = 0;
    int minX = right;
    int minY = bottom;
    int maxX = left;
    int maxY = top;
    for (int y = top; y < bottom; y += 1) {
      for (int x = left; x < right; x += 1) {
        if (isBlueInk(image.getRGB(x, y))) {
          count += 1;
          minX = Math.min(minX, x);
          minY = Math.min(minY, y);
          maxX = Math.max(maxX, x);
          maxY = Math.max(maxY, y);
        }
      }
    }
    if (count < 12) {
      return false;
    }
    int width = Math.max(1, right - left);
    int height = Math.max(1, bottom - top);
    double xCoverage = (maxX - minX + 1) / (double) width;
    double yCoverage = (maxY - minY + 1) / (double) height;
    double localDensity = count / (double) Math.max(1, (maxX - minX + 1) * (maxY - minY + 1));
    return xCoverage >= 0.28 && yCoverage >= 0.28 && localDensity <= 0.55;
  }

  private int markedColumns(boolean[] columns) {
    int count = 0;
    for (boolean column : columns) {
      if (column) {
        count += 1;
      }
    }
    return count;
  }

  private boolean isCheckboxMarkInk(int rgb) {
    int red = (rgb >> 16) & 0xff;
    int green = (rgb >> 8) & 0xff;
    int blue = rgb & 0xff;
    int average = (red + green + blue) / 3;
    boolean darkInk = average < 150;
    boolean blueInk = blue >= 90
        && blue > red + 20
        && blue > green + 8
        && (blue - red) + (blue - green) >= 55;
    return darkInk || blueInk;
  }

  private boolean hasBlueApplicantInk(BufferedImage image, List<Integer> bbox) {
    if (bbox.size() < 4) {
      return false;
    }
    int count = 0;
    int left = Math.max(0, Math.min(image.getWidth(), bbox.get(0)));
    int top = Math.max(0, Math.min(image.getHeight(), bbox.get(1)));
    int right = Math.max(left, Math.min(image.getWidth(), bbox.get(2)));
    int bottom = Math.max(top, Math.min(image.getHeight(), bbox.get(3)));
    for (int y = top; y < bottom; y += 1) {
      for (int x = left; x < right; x += 1) {
        if (isBlueInk(image.getRGB(x, y))) {
          count += 1;
        }
      }
    }
    return count >= 12;
  }

  private boolean isBlueInk(int rgb) {
    int red = (rgb >> 16) & 0xff;
    int green = (rgb >> 8) & 0xff;
    int blue = rgb & 0xff;
    return blue >= 90
        && blue > red + 20
        && blue > green + 8
        && (blue - red) + (blue - green) >= 55;
  }

  private String joinedSelectedValues(List<ApplicationTypeSelection> selections) {
    List<String> values = new ArrayList<>();
    for (ApplicationTypeSelection selection : selections) {
      String value = selection.option().value();
      if (!values.contains(value)) {
        values.add(value);
      }
    }
    return String.join("; ", values);
  }

  private List<Integer> unionBbox(List<List<Integer>> boxes) {
    List<List<Integer>> usable = boxes.stream()
        .filter(box -> box.size() >= 4)
        .toList();
    if (usable.isEmpty()) {
      return List.of();
    }
    int left = usable.stream().mapToInt(box -> box.get(0)).min().orElse(0);
    int top = usable.stream().mapToInt(box -> box.get(1)).min().orElse(0);
    int right = usable.stream().mapToInt(box -> box.get(2)).max().orElse(left);
    int bottom = usable.stream().mapToInt(box -> box.get(3)).max().orElse(top);
    return List.of(left, top, right, bottom);
  }

  private boolean shouldClear(FieldCropTranscriptionResult result, String filteredCropText) {
    String status = normalizeStatus(result.status());
    boolean blankStatus = List.of(
        "blank",
        "unchecked",
        "not_selected",
        "notselected",
        "not_checked",
        "notchecked",
        "smudged",
        "smudge",
        "scribble",
        "crossed_out",
        "crossedout",
        "correction",
        "erased"
    ).contains(status);
    boolean rejectedMarks = result.excludedMarks().isArray() && !result.excludedMarks().isEmpty();
    return result.confidence() >= MIN_BLANK_CONFIDENCE
        && filteredCropText.trim().isBlank()
        && (blankStatus || rejectedMarks);
  }

  private boolean shouldReplace(String currentValue, FieldCropTranscriptionResult result, String filteredCropText) {
    String text = filteredCropText == null ? "" : filteredCropText.trim();
    if (text.isBlank() || result.confidence() < MIN_REPLACEMENT_CONFIDENCE) {
      return false;
    }
    String status = normalizeStatus(result.status());
    if (!(status.equals("ok") || status.equals("available") || status.equals("refined"))) {
      return false;
    }
    String current = currentValue == null ? "" : currentValue.trim();
    return !text.equals(current);
  }

  private void addMissingSeparateServantRoomCandidate(
      RenderedOcrPage page,
      JsonNode pageData,
      ObjectNode mutableData,
      List<FieldCropTranscriptionRequest> requests,
      Map<String, SelectionCandidate> candidatesByKey
  ) {
    List<String> path = List.of("separate_servant_room");
    if (hasFilledValue(pageData, path)) {
      return;
    }
    String pageKey = "page_" + page.page();
    JsonNode evidenceData = metadataPage(mutableData, "_field_evidence", pageKey);
    JsonNode bedroomEvidence = firstAvailableEvidence(
        evidenceData,
        pageKey,
        List.of("number_of_bedroom"),
        List.of("number_of_bedrooms"),
        List.of("number_of_bedroom(s)")
    );
    List<Integer> bedroomBbox = parseBbox(bedroomEvidence, page.imageWidth(), page.imageHeight());
    List<Integer> servantRoomBbox = deriveRightSideOptionGroupBbox(bedroomBbox, page.imageWidth(), page.imageHeight());
    CropResult crop = crop(page, servantRoomBbox);
    if (crop.bytes().length == 0 || crop.dataUrl().isBlank()) {
      return;
    }
    String label = "Separate servant room / 獨立工人房 / 独立工人房 (Yes/No)";
    ObjectNode evidence = evidenceNode(mutableData, pageKey, path);
    evidence.put("label", label);
    putNormalizedBbox(evidence, "value_bbox", servantRoomBbox, page.imageWidth(), page.imageHeight());
    SelectionCandidate candidate = new SelectionCandidate(page.page(), path, String.join(".", path), label, "", evidence);
    candidatesByKey.put(key(page.page(), candidate.dottedPath()), candidate);
    requests.add(new FieldCropTranscriptionRequest(
        page.page(),
        candidate.dottedPath(),
        label,
        "",
        crop.bytes(),
        crop.dataUrl()
    ));
  }

  private void addMissingHouseholdIncomeDeclarationCandidate(
      RenderedOcrPage page,
      JsonNode pageData,
      ObjectNode mutableData,
      List<FieldCropTranscriptionRequest> requests,
      Map<String, SelectionCandidate> candidatesByKey
  ) {
    List<String> path = List.of("average_monthly_household_income_no_less_than_hk15000");
    if (hasFilledValue(pageData, path)) {
      return;
    }
    String pageKey = "page_" + page.page();
    JsonNode evidenceData = metadataPage(mutableData, "_field_evidence", pageKey);
    JsonNode incomeEvidence = firstAvailableEvidence(
        evidenceData,
        pageKey,
        List.of("average_monthly_household_income_no_less_than"),
        List.of("average_monthly_household_income_no_less_than_hk"),
        List.of("monthly_household_income_no_less_than"),
        List.of("household_income_no_less_than")
    );
    List<Integer> incomeBbox = parseBbox(incomeEvidence, page.imageWidth(), page.imageHeight());
    List<Integer> declarationBbox = deriveLeftSideOptionGroupBbox(incomeBbox, page.imageWidth(), page.imageHeight());
    CropResult crop = crop(page, declarationBbox);
    if (crop.bytes().length == 0 || crop.dataUrl().isBlank()) {
      return;
    }
    String label = "Average monthly household income no less than HK$15,000 declaration (Yes/No)";
    ObjectNode evidence = evidenceNode(mutableData, pageKey, path);
    evidence.put("label", label);
    putNormalizedBbox(evidence, "value_bbox", declarationBbox, page.imageWidth(), page.imageHeight());
    SelectionCandidate candidate = new SelectionCandidate(page.page(), path, String.join(".", path), label, "", evidence);
    candidatesByKey.put(key(page.page(), candidate.dottedPath()), candidate);
    requests.add(new FieldCropTranscriptionRequest(
        page.page(),
        candidate.dottedPath(),
        label,
        "",
        crop.bytes(),
        crop.dataUrl()
    ));
  }

  private void addMissingHkIdentityCardNoCandidate(
      RenderedOcrPage page,
      JsonNode pageData,
      ObjectNode mutableData,
      List<FieldCropTranscriptionRequest> requests,
      Map<String, SelectionCandidate> candidatesByKey
  ) {
    List<String> path = List.of("hk_identity_card_no");
    if (hasFilledValue(pageData, path)) {
      return;
    }
    String pageKey = "page_" + page.page();
    JsonNode evidenceData = metadataPage(mutableData, "_field_evidence", pageKey);
    JsonNode hkEvidence = firstAvailableEvidence(
        evidenceData,
        pageKey,
        path,
        List.of("hong_kong_identity_card_no"),
        List.of("hk_id_card_no")
    );
    List<Integer> hkBbox = parseBbox(hkEvidence, page.imageWidth(), page.imageHeight());
    List<Integer> optionBbox = hkBbox.isEmpty()
        ? deriveLeftSideOptionGroupBbox(
            parseBbox(firstAvailableEvidence(evidenceData, pageKey, List.of("nationality"), List.of("occupation")), page.imageWidth(), page.imageHeight()),
            page.imageWidth(),
            page.imageHeight()
        )
        : expandOptionGroupBbox(hkBbox, page.imageWidth(), page.imageHeight());
    CropResult crop = crop(page, optionBbox);
    if (crop.bytes().length == 0 || crop.dataUrl().isBlank()) {
      return;
    }
    String label = "HK identity card no. / 香港身份證號碼 / 香港身份证号码 (Yes/No)";
    ObjectNode evidence = evidenceNode(mutableData, pageKey, path);
    evidence.put("label", label);
    putNormalizedBbox(evidence, "value_bbox", optionBbox, page.imageWidth(), page.imageHeight());
    SelectionCandidate candidate = new SelectionCandidate(page.page(), path, String.join(".", path), label, "", evidence);
    candidatesByKey.put(key(page.page(), candidate.dottedPath()), candidate);
    requests.add(new FieldCropTranscriptionRequest(
        page.page(),
        candidate.dottedPath(),
        label,
        "",
        crop.bytes(),
        crop.dataUrl()
    ));
  }

  @SafeVarargs
  private final JsonNode firstAvailableEvidence(JsonNode evidenceData, String pageKey, List<String>... paths) {
    for (List<String> path : paths) {
      JsonNode evidence = lookupMetadata(evidenceData, path, pageKey);
      if (!evidence.isMissingNode() && !evidence.isNull()) {
        return evidence;
      }
    }
    return NullNode.getInstance();
  }

  private List<Integer> deriveRightSideOptionGroupBbox(List<Integer> anchorBbox, int imageWidth, int imageHeight) {
    if (anchorBbox.size() < 4) {
      return List.of();
    }
    int anchorHeight = Math.max(1, anchorBbox.get(3) - anchorBbox.get(1));
    int left = Math.min(imageWidth, anchorBbox.get(2) + Math.max(8, Math.round(imageWidth * 0.05f)));
    int right = Math.min(imageWidth, anchorBbox.get(2) + Math.max(80, Math.round(imageWidth * 0.55f)));
    int top = Math.max(0, anchorBbox.get(1) - Math.max(8, Math.round(anchorHeight * 1.35f)));
    int bottom = Math.min(imageHeight, anchorBbox.get(3) + Math.max(8, Math.round(anchorHeight * 1.35f)));
    return clampBbox(List.of(left, top, right, bottom), imageWidth, imageHeight);
  }

  private List<Integer> deriveLeftSideOptionGroupBbox(List<Integer> anchorBbox, int imageWidth, int imageHeight) {
    if (anchorBbox.size() < 4) {
      return List.of();
    }
    int anchorHeight = Math.max(1, anchorBbox.get(3) - anchorBbox.get(1));
    int left = Math.max(0, anchorBbox.get(0) - Math.max(90, Math.round(imageWidth * 0.48f)));
    int right = Math.max(left + 1, anchorBbox.get(0) - Math.max(12, Math.round(imageWidth * 0.06f)));
    int top = Math.max(0, anchorBbox.get(1) - Math.max(8, Math.round(anchorHeight * 1.25f)));
    int bottom = Math.min(imageHeight, anchorBbox.get(3) + Math.max(8, Math.round(anchorHeight * 1.25f)));
    return clampBbox(List.of(left, top, right, bottom), imageWidth, imageHeight);
  }

  private List<Integer> expandOptionGroupBbox(List<Integer> bbox, int imageWidth, int imageHeight) {
    if (bbox.size() < 4) {
      return List.of();
    }
    int height = Math.max(1, bbox.get(3) - bbox.get(1));
    int left = Math.max(0, bbox.get(0) - Math.max(18, Math.round(imageWidth * 0.06f)));
    int right = Math.min(imageWidth, bbox.get(2) + Math.max(24, Math.round(imageWidth * 0.08f)));
    int top = Math.max(0, bbox.get(1) - Math.max(8, Math.round(height * 1.25f)));
    int bottom = Math.min(imageHeight, bbox.get(3) + Math.max(8, Math.round(height * 1.25f)));
    return clampBbox(List.of(left, top, right, bottom), imageWidth, imageHeight);
  }

  private boolean hasFilledValue(JsonNode pageData, List<String> path) {
    JsonNode current = pageData;
    for (String part : path) {
      current = current.path(part);
      if (current.isMissingNode()) {
        return false;
      }
    }
    if (current.isNull()) {
      return false;
    }
    if (current.isTextual()) {
      return !current.asText().trim().isBlank();
    }
    return !current.isMissingNode();
  }

  private boolean isSelectionCandidate(List<String> path, String label, String value) {
    String joinedPath = String.join(" ", path);
    String text = (joinedPath + " " + label + " " + value).toLowerCase(Locale.ROOT);
    if (looksLikeOrdinaryValue(path, label, value)) {
      return false;
    }
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
        || text.contains("勾选")
        || text.contains("勾選")
        || text.contains("声明")
        || text.contains("聲明")
        || text.contains("拒绝")
        || text.contains("拒絕")
        || text.contains("罪");
    String labelText = label == null ? "" : label.toLowerCase(Locale.ROOT);
    if (selectionKeywords && (labelText.length() >= 36 || labelText.contains("i have"))) {
      return true;
    }
    boolean statementValue = value.trim().length() >= 36
        || value.toLowerCase(Locale.ROOT).contains("i have")
        || value.contains("本人");
    return selectionKeywords && statementValue;
  }

  private boolean looksLikeOrdinaryValue(List<String> path, String label, String value) {
    String joinedPath = String.join(" ", path).toLowerCase(Locale.ROOT);
    String shortLabel = label == null || label.length() > 40 ? "" : label.toLowerCase(Locale.ROOT);
    String text = joinedPath + " " + shortLabel;
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.matches("\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}")) {
      return true;
    }
    return text.equals("date")
        || text.endsWith(" date")
        || text.contains("date_of")
        || text.contains("signature")
        || text.contains("name")
        || text.contains("address")
        || text.contains("telephone")
        || text.contains("phone")
        || text.contains("email")
        || text.contains("e-mail");
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

  private CropResult crop(RenderedOcrPage page, List<Integer> bbox) {
    FieldCropper.CropResult crop = FieldCropper.crop(page, bbox, FieldCropper.CropKind.SELECTION);
    return new CropResult(crop.bytes(), crop.dataUrl());
  }

  private void setValue(ObjectNode root, String pageKey, List<String> path, String value) {
    ObjectNode current = objectChild(root, pageKey);
    for (int index = 0; index < path.size() - 1; index += 1) {
      current = objectChild(current, path.get(index));
    }
    if (path.isEmpty()) {
      return;
    }
    if (value == null || value.isBlank()) {
      current.putNull(path.get(path.size() - 1));
    } else {
      current.put(path.get(path.size() - 1), value);
    }
  }

  private void removeValue(ObjectNode root, String pageKey, List<String> path) {
    JsonNode current = root.path(pageKey);
    for (int index = 0; index < path.size() - 1; index += 1) {
      current = current.path(path.get(index));
      if (!current.isObject()) {
        return;
      }
    }
    if (!path.isEmpty() && current instanceof ObjectNode objectNode) {
      objectNode.remove(path.get(path.size() - 1));
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

  private void setSelectionEvidence(
      ObjectNode root,
      String pageKey,
      List<String> path,
      JsonNode existingEvidence,
      FieldCropTranscriptionResult result,
      String originalValue,
      String filteredValue
  ) {
    ObjectNode evidence = existingEvidence instanceof ObjectNode objectNode
        ? objectNode
        : evidenceNode(root, pageKey, path);
    evidence.put("selection_crop_status", result.status());
    evidence.put("selection_crop_confidence", Math.round(result.confidence()));
    evidence.put("selection_crop_text", result.text());
    if (result.excludedMarks().isArray() && !result.excludedMarks().isEmpty()) {
      evidence.put("selection_crop_text_after_excluded_marks", result.textWithoutExcludedMarks());
    }
    evidence.put("selection_filtered_out", filteredValue == null || filteredValue.isBlank());
    evidence.put("original_value", originalValue);
    if (filteredValue == null || filteredValue.isBlank()) {
      evidence.putNull("filtered_value");
    } else {
      evidence.put("filtered_value", filteredValue);
    }
    if (result.excludedMarks().isArray() && !result.excludedMarks().isEmpty()) {
      evidence.set("secondary_excluded_marks", result.excludedMarks().deepCopy());
      mergeExcludedMarks(evidence, result.excludedMarks());
    }
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

  private record FieldCandidate(List<String> path, JsonNode value) {}

  private record SelectionCandidate(
      int page,
      List<String> path,
      String dottedPath,
      String label,
      String currentValue,
      JsonNode evidence
  ) {}

  private enum ApplicationTypeSlot {
    ENTRY_TO_HK_ENTRY_VISA,
    CONTRACT_RENEWAL_ENTRY_VISA,
    CONTRACT_RENEWAL_ENTRY_VISA_AND_EXTENSION,
    REMAINING_CONTRACT_EXTENSION
  }

  private record ApplicationTypeOption(
      ApplicationTypeSlot slot,
      String fieldKey,
      String label,
      String value
  ) {}

  private record ApplicationTypeSelection(ApplicationTypeOption option, List<Integer> bbox) {}

  private record ApplicationTypeTable(
      int left,
      int top,
      int right,
      int bottom,
      int separator,
      List<HorizontalLine> lines
  ) {}

  private record HorizontalLine(int y, int left, int right, double coverage) {}

  private record CheckboxComponent(int left, int top, int right, int bottom, int pixels) {
    int centerX() {
      return (left + right) / 2;
    }
  }

  private record CheckboxBox(int left, int top, int right, int bottom) {
    int width() {
      return right - left;
    }

    int height() {
      return bottom - top;
    }

    int centerX() {
      return (left + right) / 2;
    }

    int centerY() {
      return (top + bottom) / 2;
    }
  }

  private record ScoredCheckboxBox(CheckboxBox box, double score) {}

  private record CheckboxSearchBand(int top, int bottom) {}

  private record CropResult(byte[] bytes, String dataUrl) {}
}
