import type { Me } from "./api";

let current: Me | null = null;
let loaded = false;

export function readSession() {
  return { current, loaded };
}

export function writeSession(me: Me) {
  current = me;
  loaded = true;
}

export function clearSession() {
  current = null;
  loaded = false;
}
