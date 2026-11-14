// Releasing media elements when a video is replaced.
//
// Setting a new `src` is not enough: until the old resource is explicitly
// detached, Chromium keeps the decoder, the demuxed packets and (for a
// progressive stream) the buffered bytes alive. Opening the next video while
// that is still held is what made memory spike when switching videos. Pausing
// and clearing the element lets the media pipeline release its buffers before
// the new stream is fetched.
//
// The DOM element type is narrowed to the three methods used, so this stays
// testable in the node-only vitest environment.

export interface ReleasableMedia {
  pause(): void;
  removeAttribute(name: string): void;
  load(): void;
}

/** Detach every resource a media element is holding. Never throws. */
export function releaseMediaElement(element: ReleasableMedia | null | undefined): void {
  if (!element) return;
  try {
    element.pause();
  } catch {
    /* the element may already be detached */
  }
  try {
    element.removeAttribute('src');
  } catch {
    /* nothing to release */
  }
  try {
    // Aborts the current fetch and tells Chromium to drop its buffers.
    element.load();
  } catch {
    /* already gone */
  }
}

/** Release the picture and the shadow audio track a split stream uses. */
export function releasePlayback(
  video: ReleasableMedia | null | undefined,
  audio: ReleasableMedia | null | undefined,
): void {
  releaseMediaElement(video);
  releaseMediaElement(audio);
}
