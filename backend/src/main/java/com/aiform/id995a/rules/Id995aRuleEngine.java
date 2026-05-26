package com.aiform.id995a.rules;

import com.aiform.id995a.review.AttachmentEvidence;
import com.aiform.id995a.review.FieldEvidence;
import com.aiform.id995a.review.FormReviewInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class Id995aRuleEngine {

  private static final String SOURCE_FORM = "ID995A 表格及入境处学生签证服务页";
  private static final String SOURCE_GUIDE = "ID(E)996 Guidebook for Entry for Study in Hong Kong";

  public RuleReview review(FormReviewInput input) {
    List<RuleFinding> findings = new ArrayList<>();
    FieldEvidence fields = input.fields();
    AttachmentEvidence attachments = input.attachments();

    require(findings, fields, "surnameEn", "A1-001-SURNAME", "英文姓未填写", 1);
    require(findings, fields, "givenNamesEn", "A1-001-GIVEN-NAMES", "英文名未填写", 1);
    require(findings, fields, "sex", "A1-001-SEX", "性别未选择", 1);
    require(findings, fields, "dateOfBirth", "A1-001-DOB", "出生日期未填写", 1);
    require(findings, fields, "placeOfBirth", "A1-001-BIRTH-PLACE", "出生地点未填写", 1);
    require(findings, fields, "nationalityOrDomicile", "A1-001-NATIONALITY", "国籍/原居地或定居地未填写", 1);
    require(findings, fields, "contactPhone", "A1-001-CONTACT", "联络电话未填写", 1);
    require(findings, fields, "presentAddress", "A1-001-PRESENT-ADDRESS", "现时住址未填写", 2);
    require(findings, fields, "proposedEntryDate", "A2-001-ENTRY-DATE", "拟抵港日期未填写", 2);
    require(findings, fields, "proposedDuration", "A2-001-DURATION", "拟在港逗留时间未填写", 2);
    require(findings, fields, "schoolNameAddress", "A4-001-SCHOOL", "在港就读学校名称及地址未填写", 3);
    require(findings, fields, "course", "A4-001-COURSE", "入读年级/修读课程未填写", 3);
    require(findings, fields, "livingCostTotal", "A6-001-LIVING-COST", "预计在港生活开支总计未填写", 3);
    require(findings, fields, "financialSupport", "A7-001-FINANCIAL-SUPPORT", "申请人经济状况未填写", 3);
    require(findings, fields, "photo", "A1-003-PHOTO", "第 2 页未识别到近照", 2);
    require(findings, fields, "declarationSignature", "A9-001-SIGNATURE", "申请人/父母/合法监护人声明未签署", 4);
    require(findings, fields, "declarationDate", "A9-001-DATE", "声明签署日期未填写", 4);

    if (!hasTravelDocument(fields)) {
      findings.add(blocking(
          "A1-002-TRAVEL-DOCUMENT",
          "旅行证件资料不完整",
          "旅行证件类别、号码、签发日期和届满日期须填写；如内地居民无旅行证件，至少需提供内地身份证资料并补交身份证副本。",
          1,
          "travelDocumentNo"
      ));
    }

    if (fields.isPresent("previousShortTermStudyYes") && !fields.isPresent("previousShortTermStudyDetails")) {
      findings.add(blocking(
          "A8-001-SHORT-COURSE-DETAILS",
          "短期课程资料缺失",
          "已选择过去 12 个月曾在港修读短期课程，但未填写课程名称、学校及修读日期。",
          3,
          "previousShortTermStudyDetails"
      ));
    }

    if (!attachments.hasAcceptanceLetter()) {
      findings.add(blocking(
          "DOC-001-ACCEPTANCE-LETTER",
          "缺少取录信",
          "须提交拟就读院校发出的取录信，单份 ID995A 无法证明该材料已附上。",
          3,
          "schoolNameAddress"
      ));
    }

    if (!attachments.hasApplicantTravelDocumentCopy()) {
      findings.add(blocking(
          "DOC-001-TRAVEL-DOCUMENT-COPY",
          "缺少旅行证件副本",
          "须提交有效旅行证件个人资料、签发日期、届满日期及相关签证/入境标签资料副本。",
          1,
          "travelDocumentNo"
      ));
    }

    String sponsorType = input.metadataValue("sponsorType").toLowerCase(Locale.ROOT);
    if ("institution".equals(sponsorType) && !attachments.hasApplicantFinancialProof()) {
      findings.add(blocking(
          "DOC-004-APPLICANT-FINANCIAL-PROOF",
          "缺少申请人经济证明",
          "以取录院校作为保证人时，申请人须提交经济状况证明，例如银行结单、存折、税单或薪金证明。",
          3,
          "financialSupport"
      ));
    }

    if ("individual".equals(sponsorType)) {
      if (!attachments.hasSponsorForm()) {
        findings.add(blocking("DOC-005-ID995B", "缺少 ID995B", "个人保证人须填写并提交 ID995B。", 3, "schoolNameAddress"));
      }
      if (!attachments.hasSponsorIdentityCopy()) {
        findings.add(blocking("DOC-005-SPONSOR-ID", "缺少保证人身份证明", "个人保证人须提交香港身份证或适用旅行证件副本。", 3, "schoolNameAddress"));
      }
      if (!attachments.hasSponsorFinancialProof()) {
        findings.add(blocking("DOC-005-SPONSOR-FINANCIAL", "缺少保证人经济证明", "个人保证人须提交经济能力证明及愿意提供住宿/生活费的承诺。", 3, "financialSupport"));
      }
    }

    age(input).ifPresent(age -> {
      if (age < 18) {
        if (!attachments.hasGuardianConsent()) {
          findings.add(blocking(
              "DOC-003-GUARDIAN-CONSENT",
              "未成年人缺少监护授权同意书",
              "申请人不足 18 岁时，父/母须授权保证人或在港亲友作为监护人，并提交双方签署的同意书。",
              4,
              "declarationSignature"
          ));
        }
        if (!attachments.hasAccommodationProof()) {
          findings.add(blocking(
              "DOC-003-ACCOMMODATION",
              "未成年人缺少住宿安排证明",
              "申请人不足 18 岁时，须提交住宿安排证明副本。",
              3,
              "livingCostTotal"
          ));
        }
      }

      if (age < 16 && fields.isPresent("declarationSignature")) {
        findings.add(manual(
            "A9-001-UNDER-16-SIGNATORY",
            "16 岁以下签署人需复核",
            "申请人不足 16 岁时，ID995A 须由父母或合法监护人签署；系统只识别到签名痕迹，无法确认签署人身份。",
            4,
            "declarationSignature"
        ));
      }
    });

    if (truthy(input.metadataValue("hasDependants"))) {
      require(findings, fields, "dependantName", "B1-001-DEPENDANT-NAME", "受养人姓名未填写", 5);
      require(findings, fields, "dependantSex", "B1-001-DEPENDANT-SEX", "受养人性别未选择", 5);
      require(findings, fields, "dependantDob", "B1-001-DEPENDANT-DOB", "受养人出生日期未填写", 5);
      require(findings, fields, "dependantNationality", "B1-001-DEPENDANT-NATIONALITY", "受养人国籍未填写", 5);
      require(findings, fields, "dependantTravelDocumentNo", "B1-002-DEPENDANT-TRAVEL-DOC", "受养人旅行证件号码未填写", 5);
      require(findings, fields, "dependantSignature", "B5-001-DEPENDANT-SIGNATURE", "受养人/家长签名未签署", 6);
      require(findings, fields, "dependantSignatureDate", "B5-001-DEPENDANT-SIGNATURE-DATE", "受养人声明日期未填写", 6);

      if (!attachments.dependantDocumentsComplete()) {
        findings.add(blocking(
            "DOC-008-DEPENDANTS",
            "受养人资料或材料不完整",
            "如有随行受养人，每名受养人须填妥 ID995A 乙部并提交照片、旅行证件副本和关系证明等材料。",
            5,
            "dependantPartB"
        ));
      }
    }

    if (truthy(input.metadataValue("isMainlandChineseResident")) && !attachments.mainlandApplicationViaSchool()) {
      findings.add(manual(
          "DOC-006-MAINLAND-SUBMISSION",
          "内地居民递交路径需复核",
          "内地中国居民申请必须经由取录申请人的院校（保证人）向入境处递交。",
          3,
          "schoolNameAddress"
      ));
    }

    if (truthy(input.metadataValue("documentsNeedTranslation"))) {
      findings.add(manual(
          "DOC-009-TRANSLATION",
          "非中英文文件需核证译本",
          "非中文或英文文件须附经认可人员核证的中文或英文译本。",
          1,
          "presentAddress"
      ));
    }

    return RuleReview.from(findings);
  }

  private void require(List<RuleFinding> findings, FieldEvidence fields, String fieldKey, String ruleId, String message, int page) {
    if (!fields.isPresent(fieldKey)) {
      findings.add(blocking(ruleId, "必填项缺失", message, page, fieldKey));
    }
  }

  private boolean hasTravelDocument(FieldEvidence fields) {
    boolean normalTravelDocument = fields.isPresent("travelDocumentType")
        && fields.isPresent("travelDocumentNo")
        && fields.isPresent("travelDocumentIssueDate")
        && fields.isPresent("travelDocumentExpiryDate");
    boolean mainlandIdentityFallback = fields.isPresent("mainlandIdentityNo") && fields.isPresent("nationalityOrDomicile");
    return normalTravelDocument || mainlandIdentityFallback;
  }

  private java.util.OptionalInt age(FormReviewInput input) {
    String raw = input.metadataValue("applicantAge").trim();
    if (raw.isEmpty()) {
      return java.util.OptionalInt.empty();
    }
    try {
      return java.util.OptionalInt.of(Integer.parseInt(raw));
    } catch (NumberFormatException ignored) {
      return java.util.OptionalInt.empty();
    }
  }

  private boolean truthy(String value) {
    String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
  }

  private RuleFinding blocking(String ruleId, String title, String message, int page, String fieldKey) {
    return new RuleFinding(ruleId, RuleSeverity.BLOCKING, title, message, SOURCE_GUIDE, page, fieldKey);
  }

  private RuleFinding manual(String ruleId, String title, String message, int page, String fieldKey) {
    return new RuleFinding(ruleId, RuleSeverity.MANUAL_REVIEW, title, message, SOURCE_FORM, page, fieldKey);
  }
}
