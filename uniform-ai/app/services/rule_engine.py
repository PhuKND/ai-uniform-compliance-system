from __future__ import annotations

from uniform_validation.labels import REQUIRED_COMPONENT_KEYS


class RuleEngine:
    REQUIRED_ITEM_KEYS = REQUIRED_COMPONENT_KEYS
    LOWER_BODY_KEYS = ["quan_tay_dai_den", "quan_short_tay_den", "quan_dai_trang"]
    APPEARANCE_VALUE = {"pass": 1.0, "uncertain": 0.5, "fail": 0.0}

    def __init__(self, config) -> None:
        self.config = config

    @staticmethod
    def _clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
        return max(low, min(high, value))

    @staticmethod
    def _dedupe_notes(notes: list[str]) -> list[str]:
        out: list[str] = []
        seen: set[str] = set()
        for note in notes:
            note = note.strip()
            if not note or note in seen:
                continue
            out.append(note)
            seen.add(note)
        return out

    def _policy_status(self, required_items: dict) -> dict:
        policy = self.config.UNIFORM_SHIRT_POLICY
        require_red_scarf = bool(self.config.REQUIRE_RED_SCARF)

        white_present = bool(required_items["ao_so_mi_trang"]["present"])
        youth_union_present = bool(required_items["ao_doan_thanh_nien"]["present"])
        lower_present = any(bool(required_items[key]["present"]) for key in self.LOWER_BODY_KEYS)

        if policy == "white_only":
            shirt_requirement_passed = white_present
            shirt_requirement = ["ao_so_mi_trang"]
        elif policy == "youth_union_only":
            shirt_requirement_passed = youth_union_present
            shirt_requirement = ["ao_doan_thanh_nien"]
        else:
            shirt_requirement_passed = white_present or youth_union_present
            shirt_requirement = ["ao_so_mi_trang", "ao_doan_thanh_nien"]

        requirement_results = [
            ("shirt_requirement", shirt_requirement_passed, shirt_requirement),
            ("lower_body_requirement", lower_present, self.LOWER_BODY_KEYS),
        ]

        if require_red_scarf:
            requirement_results.append(
                ("khan_quang_do", bool(required_items["khan_quang_do"]["present"]), ["khan_quang_do"])
            )

        passed_count = sum(1 for _, passed, _ in requirement_results if passed)
        total_count = len(requirement_results)
        missing = [options for _, passed, options in requirement_results if not passed]

        return {
            "shirt_policy": policy,
            "shirt_requirement_passed": shirt_requirement_passed,
            "shirt_requirement": shirt_requirement,
            "require_red_scarf": require_red_scarf,
            "passed_component_count": passed_count,
            "required_component_count": total_count,
            "all_required_components_present": passed_count == total_count,
            "missing_requirements": missing,
        }

    def _overall_label(self, required_ratio: float, all_required_present: bool, fail_count: int, uncertain_count: int) -> str:
        if required_ratio < 0.67 or fail_count >= 2:
            return "non_compliant"
        if uncertain_count >= 2:
            return "needs_review"
        if all_required_present and fail_count == 0 and uncertain_count == 0:
            return "compliant"
        if required_ratio >= 0.67:
            return "partially_compliant"
        return "needs_review"

    def aggregate(self, required_items: dict, appearance: dict, notes: list[str] | None = None) -> dict:
        clean_required = {}
        for key in self.REQUIRED_ITEM_KEYS:
            item = required_items.get(key, {})
            present = bool(item.get("present", False))
            score = round(float(self._clamp(float(item.get("score", 0.0)))), 3)
            clean_required[key] = {"present": present, "score": score}

        clean_appearance = {}
        for key in ["tucked_in", "wrinkled", "dirty", "torn_or_damaged"]:
            item = appearance.get(key, {})
            label = item.get("label", "uncertain")
            if label not in {"pass", "fail", "uncertain"}:
                label = "uncertain"
            score = round(float(self._clamp(float(item.get("score", 0.0)))), 3)
            clean_appearance[key] = {"label": label, "score": score}

        policy_status = self._policy_status(clean_required)
        fail_count = sum(1 for value in clean_appearance.values() if value["label"] == "fail")
        uncertain_count = sum(1 for value in clean_appearance.values() if value["label"] == "uncertain")

        required_ratio = policy_status["passed_component_count"] / max(1, policy_status["required_component_count"])
        appearance_ratio = sum(self.APPEARANCE_VALUE[v["label"]] for v in clean_appearance.values()) / max(
            1, len(clean_appearance)
        )
        overall_score = round(self._clamp((0.65 * required_ratio) + (0.35 * appearance_ratio)), 3)
        overall_label = self._overall_label(
            required_ratio,
            policy_status["all_required_components_present"],
            fail_count,
            uncertain_count,
        )

        generated_notes: list[str] = []
        if policy_status["missing_requirements"]:
            missing_text = [
                " or ".join(options) if len(options) > 1 else options[0]
                for options in policy_status["missing_requirements"]
            ]
            generated_notes.append(f"Missing or low-confidence uniform requirements: {', '.join(missing_text)}.")

        failed_appearance = [k for k, value in clean_appearance.items() if value["label"] == "fail"]
        if failed_appearance:
            generated_notes.append(f"Appearance violations detected: {', '.join(failed_appearance)}.")

        uncertain_appearance = [k for k, value in clean_appearance.items() if value["label"] == "uncertain"]
        if uncertain_appearance:
            generated_notes.append(f"Low-confidence checks: {', '.join(uncertain_appearance)}.")

        if overall_label == "needs_review":
            generated_notes.append("Automatic confidence is limited; manual inspection is advised.")

        all_notes = self._dedupe_notes((notes or []) + generated_notes)

        return {
            "required_items": clean_required,
            "uniform_policy": {
                "shirt_policy": policy_status["shirt_policy"],
                "shirt_requirement_passed": policy_status["shirt_requirement_passed"],
                "shirt_requirement": policy_status["shirt_requirement"],
                "require_red_scarf": policy_status["require_red_scarf"],
                "passed_component_count": policy_status["passed_component_count"],
                "required_component_count": policy_status["required_component_count"],
            },
            "appearance": clean_appearance,
            "overall": {"compliance": overall_label, "score": overall_score},
            "notes": all_notes,
        }
