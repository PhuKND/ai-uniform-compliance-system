import type { ComponentEvidence } from "../types";
import { asPercent, componentLabel } from "../utils/format";

interface ComponentListProps {
  title: string;
  items: ComponentEvidence[] | string[] | null | undefined;
  empty: string;
  tone?: "success" | "warning" | "danger";
}

export function ComponentList({ title, items, empty, tone = "success" }: ComponentListProps) {
  const normalized = Array.isArray(items) ? items : [];
  return (
    <div className="component-list">
      <h4>{title}</h4>
      {normalized.length === 0 ? (
        <p className="muted small">{empty}</p>
      ) : (
        <div className="chip-list">
          {normalized.map((item, index) => {
            const key = typeof item === "string" ? item : item.class_name ?? item.label ?? String(index);
            const confidence = typeof item === "string" ? null : item.confidence;
            return (
              <span className={`component-chip ${tone}`} key={`${key}-${index}`}>
                {componentLabel(key)}
                {confidence != null ? <small>{asPercent(confidence)}</small> : null}
              </span>
            );
          })}
        </div>
      )}
    </div>
  );
}
