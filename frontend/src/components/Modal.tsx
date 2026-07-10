import { IconX as X } from "@tabler/icons-react";
import type { PropsWithChildren } from "react";

interface ModalProps extends PropsWithChildren {
  title: string;
  onClose: () => void;
  wide?: boolean;
}

export function Modal({ title, onClose, wide, children }: ModalProps) {
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label={title}>
      <div className={`modal-panel ${wide ? "wide" : ""}`}>
        <div className="modal-header">
          <h2>{title}</h2>
          <button className="icon-button" type="button" onClick={onClose} aria-label="Đóng">
            <X size={18} />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}
