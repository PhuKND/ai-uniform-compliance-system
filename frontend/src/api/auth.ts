import { api, unwrap } from "./http";
import type { AuthResponse } from "../types";

export function login(email: string, password: string) {
  return unwrap<AuthResponse>(api.post("/api/auth/login", { email, password }));
}
