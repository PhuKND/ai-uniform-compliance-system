export type Role = "ADMIN" | "STUDENT";

export type Gender = "MALE" | "FEMALE" | "OTHER";

export type ComplianceStatus = "COMPLIANT" | "PARTIALLY_COMPLIANT" | "NON_COMPLIANT" | "NEEDS_REVIEW";

export type CorrectionStatus = "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED";

export type MethodProcessingStatus = "pending" | "processing" | "completed" | "failed";

export type ComparisonJobStatus = "processing" | "partial" | "completed" | "failed";

export type EvaluationMethod =
  | "METHOD_1_GROUNDING_DINO_SCHP_FLORENCE"
  | "METHOD_2_YOLOV8_SCHP_FLORENCE"
  | "METHOD_1_POSE_INSIGHTFACE_GROUNDING_DINO"
  | "METHOD_2_POSE_INSIGHTFACE_YOLOV8_UNIFORM"
  | "GROUNDING_DINO_V2"
  | "YOLOV8_V2"
  | "LIGHTWEIGHT_GROUNDING_DINO"
  | "LIGHTWEIGHT_YOLOV8_UNIFORM";

export type Weekday =
  | "MONDAY"
  | "TUESDAY"
  | "WEDNESDAY"
  | "THURSDAY"
  | "FRIDAY"
  | "SATURDAY"
  | "SUNDAY";

export type UniformComponentKey =
  | "ao_so_mi_trang"
  | "ao_doan_thanh_nien"
  | "quan_tay_dai_den"
  | "khan_quang_do"
  | "quan_short_tay_den"
  | "quan_dai_trang";

export interface ApiEnvelope<T> {
  success: boolean;
  message: string;
  data: T;
  timestamp: string;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
  numberOfElements: number;
}

export interface Student {
  id: number;
  studentCode: string;
  faceDataId: string;
  fullName: string;
  gender: Gender | null;
  dateOfBirth: string | null;
  age: number | null;
  className: string | null;
  schoolYear: string | null;
  phone: string | null;
  email: string | null;
  address: string | null;
  moralityScore: number;
  moralityLevel: string;
  moralityLevelCode: string;
  active: boolean;
  deletionRequested: boolean;
  hasAccount: boolean;
  accountUsername: string | null;
  accountEmail: string | null;
  accountEnabled: boolean | null;
  createdAt: string;
  updatedAt: string;
}

export interface StudentInput {
  fullName?: string;
  gender?: Gender | "";
  dateOfBirth?: string;
  className?: string;
  schoolYear?: string;
  phone?: string;
  email?: string;
  address?: string;
  moralityScore?: number;
  active?: boolean;
}

export interface FaceDataStatus {
  studentCode: string;
  fullName: string;
  faceDataId: string;
  enrolled: boolean;
  sampleCount: number;
  lastRecordId: number | null;
  lastSyncedAt: string | null;
}

export interface AuthResponse {
  tokenType: string;
  accessToken: string;
  userId: number;
  username: string | null;
  email: string;
  role: Role;
  student: Student | null;
}

export interface StudentAccountInput {
  username: string;
  email: string;
  password: string;
  confirmPassword?: string;
}

export interface StudentAccountResponse {
  userId: number;
  username: string | null;
  email: string;
  role: Role;
  enabled: boolean;
  student: Student;
}

export interface UniformClassOption {
  classId: string;
  className: string;
  studentCount: number;
}

export interface UniformComponentOption {
  key: UniformComponentKey;
  label: string;
}

export interface UniformRequirementScheduleDay {
  dayOfWeek: Weekday;
  dayLabel: string;
  configured: boolean;
  requiredComponents: UniformComponentKey[];
  requiredComponentDetails: UniformComponentOption[];
  updatedAt: string | null;
}

export interface UniformRequirementScheduleResponse {
  classId: string;
  className: string;
  timeZone: string;
  componentOptions: UniformComponentOption[];
  schedules: UniformRequirementScheduleDay[];
  updatedAt: string | null;
}

export interface UniformRequirementScheduleDayInput {
  dayOfWeek: Weekday;
  requiredComponents: UniformComponentKey[];
}

export interface UniformRequirementScheduleUpdateInput {
  schedules: UniformRequirementScheduleDayInput[];
}

export interface ScheduleComplianceResult {
  configured: boolean;
  applicable: boolean;
  reason: string | null;
  className: string | null;
  dayOfWeek: Weekday | null;
  dayLabel: string | null;
  timeZone: string | null;
  evaluatedAt: string | null;
  requiredComponents: UniformComponentKey[];
  detectedComponents: UniformComponentKey[];
  missingComponents: UniformComponentKey[];
  missingRequiredComponentCount: number | null;
  score: number | null;
  deductedPoints: number | null;
  automaticConductDeduction?: number | null;
  complianceStatus: ComplianceStatus | null;
  snapshot?: Record<string, unknown> | null;
}

export interface StudentUniformScheduleComponent {
  code: UniformComponentKey;
  name: string;
}

export interface StudentUniformScheduleDay {
  dayOfWeek: Weekday;
  displayName: string;
  isToday: boolean;
  hasSchedule: boolean;
  requiredComponents: StudentUniformScheduleComponent[];
}

export interface StudentUniformScheduleResponse {
  studentId: string;
  studentCode: string;
  studentName: string;
  className: string | null;
  timeZone: string;
  today: Weekday;
  days: StudentUniformScheduleDay[];
}

export interface MethodResult {
  method: EvaluationMethod;
  complianceStatus: ComplianceStatus;
  processedImageId: number | null;
  processedImageUrl: string | null;
  aiProcessedImageUrl: string | null;
  rawResult: Record<string, unknown> | null;
  methodKey: string;
  methodDisplayName: string;
  processedImagePath: string | null;
  result: CandidateResult | null;
  status?: MethodProcessingStatus;
  score?: number | null;
  resultStatus?: string | null;
  validComponents?: ComponentEvidence[] | null;
  missingComponents?: string[] | null;
  excludedComponents?: ComponentEvidence[] | null;
  message?: string | null;
  note?: string | null;
  error?: string | null;
  completedAt?: string | null;
  evaluationMethod?: string | null;
  detectorModelId?: string | null;
  detectorModelVersion?: string | null;
  detectorConfidenceThreshold?: number | Record<string, number> | null;
  rawDetectionCount?: number | null;
  poseAcceptedDetectionCount?: number | null;
  finalUniqueDetectionCount?: number | null;
  duplicateRemovedCount?: number | null;
  scheduleResult?: ScheduleComplianceResult | null;
  detectorTrace?: CandidateResult["detector_trace"] | null;
}

export interface EvaluationCompareResponse {
  runId: number;
  requestedStudentCode: string | null;
  recognizedStudentCode: string | null;
  originalImageId: number | null;
  method1: MethodResult;
  method2: MethodResult;
  createdAt: string;
  uniformAiEvaluationId: string | null;
  preAiImagePath: string | null;
  preAiImageUrl: string | null;
  originalImageUrl: string | null;
  student: Student | null;
  candidates: MethodResult[];
  jobId?: number;
  status?: ComparisonJobStatus;
  updatedAt?: string;
  results?: MethodResult[];
}

export interface ComponentEvidence {
  label?: string;
  class_name?: string;
  confidence?: number;
  source_label?: string;
  pose_overlap_ratio?: number;
  validation_reason?: string;
  reason?: string;
  message?: string;
  duplicate_of_detection_id?: string;
}

export interface CandidateResult {
  method?: string;
  required_components?: string[];
  accepted_components?: ComponentEvidence[];
  missing_components?: string[];
  rejected_components?: ComponentEvidence[];
  removed_duplicate_components?: ComponentEvidence[];
  removed_duplicate_detections?: ComponentEvidence[];
  detector_trace?: {
    raw_detections?: ComponentEvidence[];
    pose_accepted_detections?: ComponentEvidence[];
    final_unique_per_class_detections?: ComponentEvidence[];
    removed_duplicate_detections?: ComponentEvidence[];
  };
  tuck_in_assessment?: {
    available?: boolean;
    skipped?: boolean;
    tucked_in?: boolean | null;
    status?: string;
    confidence?: number;
    explanation?: string;
  };
  appearance_assessment?: {
    available?: boolean;
    skipped?: boolean;
    wrinkled?: boolean | null;
    dirty?: boolean | null;
    torn?: boolean | null;
    description?: string;
    model_description?: string;
    confidence?: Record<string, number>;
  };
  final_summary?: {
    is_compliant?: boolean | null;
    score?: number;
    vietnamese_comment?: string;
    legacy_compliance?: string;
  };
  backend_final_result?: {
    status?: ComplianceStatus;
    compliance_status?: ComplianceStatus;
    canonical_score?: number;
    overallCompliant?: boolean;
    overall_compliant?: boolean;
    finalScore?: number;
    final_score?: number;
    automatic_conduct_deduction?: number;
    deducted_points?: number;
    finalComment?: string;
    final_comment?: string;
    missingComponents?: string[];
    missing_components?: string[];
    violationTypes?: string[];
    violation_types?: string[];
    violationSummary?: string;
    violation_summary?: string;
    review_issue?: boolean;
    review_reasons?: string[];
  };
  backend_schedule_result?: ScheduleComplianceResult;
  processed_image_url?: string;
  processed_image_path?: string;
  lightweight_no_schp_no_florence?: boolean;
}

export interface ChooseOfficialRequest {
  selectedMethod: string;
  studentCode?: string;
  deductedPoints?: number;
  adminNote?: string;
}

export interface EvaluationHistory {
  id: number;
  studentCode: string;
  studentName: string;
  className: string | null;
  dateOfBirth: string | null;
  studentAgeAtEvaluation: number | null;
  recognizedStudentCode: string | null;
  uniformAiEvaluationId: string | null;
  selectedMethod: EvaluationMethod;
  complianceStatus: ComplianceStatus;
  hasWhiteShirt: boolean;
  hasYouthUnionShirt: boolean;
  hasBlackTrousers: boolean;
  hasRedScarf: boolean;
  shirtTuckedIn: boolean | null;
  clothesWrinkled: boolean | null;
  clothesDirty: boolean | null;
  clothesTorn: boolean | null;
  overallCompliant: boolean;
  violationTypes: string[];
  violationSummary: string | null;
  aiComment: string | null;
  deductedPoints: number;
  originalImageId: number | null;
  processedImageId: number | null;
  originalImageUrl: string | null;
  processedImageUrl: string | null;
  preAiImagePath: string | null;
  preAiImageUrl: string | null;
  selectedProcessedImagePath: string | null;
  selectedProcessedImageUrl: string | null;
  finalScore: number | null;
  finalComment: string | null;
  scheduleConfigured: boolean;
  scheduleApplicable: boolean;
  scheduleReason: string | null;
  scheduleClassName: string | null;
  scheduleDayOfWeek: Weekday | null;
  scheduleDayLabel: string | null;
  scheduleTimeZone: string | null;
  scheduleEvaluatedAt: string | null;
  scheduleScore: number | null;
  scheduleDeductedPoints: number | null;
  scheduleRequiredComponents: UniformComponentKey[] | null;
  scheduleDetectedComponents: UniformComponentKey[] | null;
  scheduleMissingComponents: UniformComponentKey[] | null;
  scheduleSnapshot: Record<string, unknown> | null;
  acceptedComponents: ComponentEvidence[] | null;
  missingComponents: string[] | null;
  rejectedComponents: ComponentEvidence[] | null;
  tuckInAssessment: CandidateResult["tuck_in_assessment"] | null;
  appearanceAssessment: CandidateResult["appearance_assessment"] | null;
  createdBy: string;
  adminNote: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AdminStatistics {
  totalStudents?: number;
  studentsByClass?: Record<string, number>;
  studentsByMoralityLevel?: Record<string, number>;
  averageMoralityScoreByClass?: Record<string, number>;
  lowMoralityStudents?: Array<{
    studentCode: string;
    fullName: string;
    className: string | null;
    moralityScore: number;
    moralityLevel: string;
  }>;
  totalEvaluations?: number;
  evaluationsByStatus?: Record<string, number>;
  averageCanonicalComplianceScore?: number | null;
  scoreDistribution?: Record<string, number>;
  conductDeductionTotal?: number;
  averageCanonicalComplianceScoreByClass?: Record<string, number>;
  conductDeductionByClass?: Record<string, number>;
  totalViolations?: number;
  violationsByType?: Record<string, number>;
  evaluationsByClass?: Record<string, number>;
  methodComparison?: Record<string, number>;
  studentsWithMostViolations?: Array<{
    studentCode: string;
    studentName: string;
    violationCount: number;
  }>;
  correctionRequestStatusCounts?: Record<string, number>;
}

export interface CorrectionRequest {
  id: number;
  evaluationHistoryId: number;
  studentCode: string;
  studentName: string;
  deductionAtSubmission: number | null;
  requestedDeduction: number | null;
  deductionAfterDecision: number | null;
  reason: string;
  evidenceNote: string | null;
  evidenceImageId: number | null;
  evidenceImageUrl: string | null;
  status: CorrectionStatus;
  adminResponseNote: string | null;
  resolvedBy: string | null;
  resolvedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ResolveCorrectionRequestInput {
  adminResponseNote?: string;
  updatedViolationSummary?: string;
}

export interface RealtimeCameraKeypoint {
  name: string;
  x: number;
  y: number;
  confidence: number | null;
}

export interface RealtimeCameraPoseLink {
  from: string;
  to: string;
}

export interface RealtimeCameraPerson {
  bbox: number[];
  confidence: number | null;
  keypoints: RealtimeCameraKeypoint[];
  skeleton: RealtimeCameraPoseLink[];
}

export interface RealtimeCameraFace {
  bbox: number[];
  confidence: number | null;
}

export interface RealtimeCameraIdentity {
  matched: boolean;
  studentId: number | null;
  studentCode: string | null;
  fullName: string | null;
  className: string | null;
  confidence: number | null;
  label: string;
}

export interface RealtimeUniformDetection {
  className: string;
  confidence: number | null;
  bbox: number[];
}

export interface RealtimeCameraAnalysis {
  success: boolean;
  message: string;
  frameWidth: number | null;
  frameHeight: number | null;
  processingTimeMs: number | null;
  selectedPerson: RealtimeCameraPerson | null;
  identity: RealtimeCameraIdentity | null;
  face: RealtimeCameraFace | null;
  uniformDetections: RealtimeUniformDetection[];
  pipeline: string;
}
