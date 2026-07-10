import type { UniformComponentKey } from "../types";

export const SHIRT_COMPONENTS: UniformComponentKey[] = ["ao_so_mi_trang", "ao_doan_thanh_nien"];
export const LOWER_BODY_COMPONENTS: UniformComponentKey[] = [
  "quan_tay_dai_den",
  "quan_short_tay_den",
  "quan_dai_trang",
];

export function warningForScheduleToggle(
  currentComponents: UniformComponentKey[],
  componentKey: UniformComponentKey,
  nextEnabled: boolean,
) {
  if (!nextEnabled) return null;

  const current = new Set(currentComponents);
  const next = new Set(currentComponents);
  next.add(componentKey);

  const hadBothShirts = SHIRT_COMPONENTS.every((key) => current.has(key));
  const hasBothShirts = SHIRT_COMPONENTS.every((key) => next.has(key));
  if (!hadBothShirts && hasBothShirts) {
    return "Bạn có chắc chắn rằng yêu cầu học sinh mặc một lần 2 loại áo?";
  }

  if (LOWER_BODY_COMPONENTS.includes(componentKey)) {
    const beforeLowerCount = LOWER_BODY_COMPONENTS.filter((key) => current.has(key)).length;
    const afterLowerCount = LOWER_BODY_COMPONENTS.filter((key) => next.has(key)).length;
    if (beforeLowerCount < 2 && afterLowerCount === 2) {
      return "Bạn có chắc chắn rằng yêu cầu học sinh mặc một lần 2 loại quần?";
    }
    if (beforeLowerCount < 3 && afterLowerCount === 3) {
      return "Bạn có chắc chắn rằng yêu cầu học sinh mặc một lần 3 loại quần?";
    }
  }

  const hadYouthUnionScarf = current.has("ao_doan_thanh_nien") && current.has("khan_quang_do");
  const hasYouthUnionScarf = next.has("ao_doan_thanh_nien") && next.has("khan_quang_do");
  if (!hadYouthUnionScarf && hasYouthUnionScarf) {
    return "Bạn có chắc chắn rằng yêu cầu học sinh vừa mặc áo đoàn thanh niên vừa thắt khăn quàng đỏ";
  }

  return null;
}
