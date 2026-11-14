//! One yt-dlp run per video, however many callers ask at the same moment.
//!
//! Hovering a card warms the stream ladder, the watch page warms it again, and
//! the player asks for it a third time as soon as it mounts. Each of those is a
//! 5-25 s `yt-dlp --dump-single-json`, so without coordination a single click
//! pays for two or three extractions of the same video — which is exactly what
//! "the video takes ages to start" feels like. The first caller runs the work;
//! the others wait on the same slot and share its outcome.
//!
//! `electron/streams.js` solved this with a `videoId -> Promise` map. This is
//! the same idea with a condvar, and it is generic enough to test without
//! yt-dlp or a network.

use std::collections::HashMap;
use std::sync::{Arc, Condvar, Mutex, MutexGuard};

struct Slot<T: Clone> {
    settled: Condvar,
    outcome: Mutex<Option<Result<T, String>>>,
}

impl<T: Clone> Default for Slot<T> {
    fn default() -> Self {
        Self {
            settled: Condvar::new(),
            outcome: Mutex::new(None),
        }
    }
}

pub struct SingleFlight<T: Clone> {
    running: Mutex<HashMap<String, Arc<Slot<T>>>>,
}

impl<T: Clone> Default for SingleFlight<T> {
    fn default() -> Self {
        Self::new()
    }
}

impl<T: Clone> SingleFlight<T> {
    pub fn new() -> Self {
        Self {
            running: Mutex::new(HashMap::new()),
        }
    }

    fn lock(&self) -> MutexGuard<'_, HashMap<String, Arc<Slot<T>>>> {
        self.running
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// True while some thread is producing this key.
    pub fn is_running(&self, key: &str) -> bool {
        self.lock().contains_key(key)
    }

    /// How many runs are in flight right now. Background warm-up watches this:
    /// a sidecar that is already resolving the video the viewer picked must
    /// not be joined by eight more, or both end up slower than doing nothing.
    pub fn active(&self) -> usize {
        self.lock().len()
    }

    /// Drop our slot from the map, but never somebody else's: a caller that
    /// started after us owns the key by then.
    fn release(&self, key: &str, slot: &Arc<Slot<T>>) {
        let mut running = self.lock();
        if running
            .get(key)
            .map(|existing| Arc::ptr_eq(existing, slot))
            .unwrap_or(false)
        {
            running.remove(key);
        }
    }

    /// Run `produce` for `key`, sharing one run between concurrent callers.
    /// A follower that arrives after the leader finished with an error becomes
    /// the next leader, so a retry is always possible.
    pub fn run<F>(&self, key: &str, produce: F) -> Result<T, String>
    where
        F: FnOnce() -> Result<T, String>,
    {
        let (slot, leader) = {
            let mut running = self.lock();
            match running.get(key) {
                Some(existing) => (Arc::clone(existing), false),
                None => {
                    let fresh = Arc::<Slot<T>>::default();
                    running.insert(key.to_string(), Arc::clone(&fresh));
                    (fresh, true)
                }
            }
        };

        if !leader {
            let mut outcome = slot
                .outcome
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            while outcome.is_none() {
                outcome = slot
                    .settled
                    .wait(outcome)
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
            }
            return outcome
                .clone()
                .unwrap_or_else(|| Err("the stream lookup ended without an answer".to_string()));
        }

        // Publishes an outcome and releases the slot even if `produce` unwinds,
        // so a follower can never be stranded by a leader that died.
        let mut guard = Leader {
            flights: self,
            key: key.to_string(),
            slot,
            settled: false,
        };
        let result = produce();
        guard.finish(result.clone());
        result
    }
}

struct Leader<'a, T: Clone> {
    flights: &'a SingleFlight<T>,
    key: String,
    slot: Arc<Slot<T>>,
    settled: bool,
}

impl<T: Clone> Leader<'_, T> {
    fn finish(&mut self, result: Result<T, String>) {
        self.settled = true;
        {
            let mut outcome = self
                .slot
                .outcome
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            *outcome = Some(result);
        }
        self.slot.settled.notify_all();
        self.flights.release(&self.key, &self.slot);
    }
}

impl<T: Clone> Drop for Leader<'_, T> {
    fn drop(&mut self) {
        if !self.settled {
            let mut guard = self
                .slot
                .outcome
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            if guard.is_none() {
                *guard = Some(Err("the stream lookup stopped unexpectedly".to_string()));
            }
            drop(guard);
            self.slot.settled.notify_all();
            self.flights.release(&self.key, &self.slot);
        }
    }
}

/// A short-lived "we already tried that" note: a video YouTube will not serve is
/// answered immediately instead of waiting on another doomed extraction.
#[derive(Default)]
pub struct MissCache {
    misses: Mutex<HashMap<String, u64>>,
}

impl MissCache {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn remember(&self, key: &str, at: u64) {
        let mut misses = self.misses.lock().unwrap_or_else(|p| p.into_inner());
        misses.insert(key.to_string(), at);
        // A bounded note; the oldest is the least likely to be asked for again.
        if misses.len() > 200 {
            let oldest = misses
                .iter()
                .min_by_key(|(_, at)| **at)
                .map(|(k, _)| k.clone());
            if let Some(key) = oldest {
                misses.remove(&key);
            }
        }
    }

    pub fn forget(&self, key: &str) {
        self.misses
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(key);
    }

    pub fn held(&self, key: &str, now: u64, ttl_ms: u64) -> bool {
        let misses = self.misses.lock().unwrap_or_else(|p| p.into_inner());
        match misses.get(key) {
            Some(at) => now.saturating_sub(*at) < ttl_ms,
            None => false,
        }
    }
}

/// The videos the UI has just shown, waiting to have their streams resolved in
/// the background so the next click is a cache hit.
///
/// It lives next to the single-flight rather than in the page because the shell
/// is the only thing that can tell whether asking now would get in the viewer's
/// way, and because three screens each keeping their own queue would end up
/// asking for the same videos three times.
#[derive(Default)]
pub struct Prefetch {
    waiting: Mutex<std::collections::VecDeque<String>>,
    /// A drain is already running. One at a time is the whole point: the queue
    /// exists to keep background work from arriving all at once.
    busy: std::sync::atomic::AtomicBool,
}

/// How many videos may wait. Past this the oldest are dropped: they belong to a
/// page that has already scrolled away.
pub const PREFETCH_CAP: usize = 12;

impl Prefetch {
    fn queue(&self) -> MutexGuard<'_, std::collections::VecDeque<String>> {
        self.waiting
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// Take the right to drain the queue, if nobody else has it.
    pub fn claim(&self) -> bool {
        !self
            .busy
            .swap(true, std::sync::atomic::Ordering::SeqCst)
    }

    pub fn release(&self) {
        self.busy.store(false, std::sync::atomic::Ordering::SeqCst);
    }

    /// Add these ids to the back, ignoring the ones already waiting.
    pub fn push(&self, ids: &[String]) {
        let mut waiting = self.queue();
        for id in ids {
            if id.is_empty() || waiting.iter().any(|waiting| waiting == id) {
                continue;
            }
            waiting.push_back(id.clone());
        }
        while waiting.len() > PREFETCH_CAP {
            waiting.pop_front();
        }
    }

    /// Put one back at the front, for when now is the wrong moment to ask.
    pub fn requeue(&self, id: &str) {
        let mut waiting = self.queue();
        if !waiting.iter().any(|waiting| waiting == id) {
            waiting.push_front(id.to_string());
        }
    }

    pub fn next(&self) -> Option<String> {
        self.queue().pop_front()
    }

    pub fn len(&self) -> usize {
        self.queue().len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::time::Duration;

    #[test]
    fn concurrent_callers_share_one_run() {
        let flights: SingleFlight<u32> = SingleFlight::new();
        let runs = Arc::new(AtomicUsize::new(0));
        let answers = std::thread::scope(|scope| {
            let handles: Vec<_> = (0..6)
                .map(|_| {
                    let flights = &flights;
                    let runs = Arc::clone(&runs);
                    scope.spawn(move || {
                        flights.run("abc123", || {
                            runs.fetch_add(1, Ordering::SeqCst);
                            // Long enough that every other thread is definitely
                            // waiting on this one.
                            std::thread::sleep(Duration::from_millis(150));
                            Ok(7)
                        })
                    })
                })
                .collect();
            handles
                .into_iter()
                .map(|h| h.join().unwrap().unwrap())
                .collect::<Vec<u32>>()
        });
        assert_eq!(answers, vec![7; 6]);
        assert_eq!(
            runs.load(Ordering::SeqCst),
            1,
            "yt-dlp would have run six times"
        );
        assert!(!flights.is_running("abc123"), "the slot is released");
    }

    #[test]
    fn different_videos_do_not_queue_behind_each_other() {
        let flights: SingleFlight<String> = SingleFlight::new();
        let runs = Arc::new(AtomicUsize::new(0));
        let mut got = std::thread::scope(|scope| {
            let handles: Vec<_> = ["one", "two", "three"]
                .into_iter()
                .map(|id| {
                    let flights = &flights;
                    let runs = Arc::clone(&runs);
                    scope.spawn(move || {
                        flights.run(id, || {
                            runs.fetch_add(1, Ordering::SeqCst);
                            Ok(id.to_string())
                        })
                    })
                })
                .collect();
            handles
                .into_iter()
                .map(|h| h.join().unwrap().unwrap())
                .collect::<Vec<String>>()
        });
        got.sort();
        assert_eq!(got, vec!["one".to_string(), "three".to_string(), "two".to_string()]);
        assert_eq!(runs.load(Ordering::SeqCst), 3);
    }

    #[test]
    fn a_failed_run_is_shared_and_a_later_call_can_retry() {
        let flights: SingleFlight<u32> = SingleFlight::new();
        let runs = Arc::new(AtomicUsize::new(0));
        let errors = std::thread::scope(|scope| {
            let handles: Vec<_> = (0..3)
                .map(|_| {
                    let flights = &flights;
                    let runs = Arc::clone(&runs);
                    scope.spawn(move || {
                        flights.run("broken", || {
                            runs.fetch_add(1, Ordering::SeqCst);
                            std::thread::sleep(Duration::from_millis(80));
                            Err("Video unavailable".to_string())
                        })
                    })
                })
                .collect();
            handles
                .into_iter()
                .map(|h| h.join().unwrap().unwrap_err())
                .collect::<Vec<String>>()
        });
        assert!(errors.iter().all(|e| e == "Video unavailable"));
        assert_eq!(runs.load(Ordering::SeqCst), 1);

        // The slot is gone, so the next attempt actually runs again.
        let again = flights.run("broken", || Ok(1));
        assert_eq!(again, Ok(1));
        assert_eq!(runs.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn a_panicking_producer_releases_the_waiters_and_the_key() {
        // Nobody may be left waiting on a leader that died mid-extraction.
        let flights: SingleFlight<u32> = SingleFlight::new();
        let crashed = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            flights.run("boom", || -> Result<u32, String> { panic!("yt-dlp died") })
        }));
        assert!(crashed.is_err());
        assert!(!flights.is_running("boom"));
        assert_eq!(flights.run("boom", || Ok(3)), Ok(3));
    }

    #[test]
    fn misses_expire_and_clear() {
        let misses = MissCache::new();
        misses.remember("v1", 1_000);
        assert!(misses.held("v1", 1_500, 60_000));
        assert!(!misses.held("v1", 62_000, 60_000));
        assert!(!misses.held("never-tried", 1_500, 60_000));
        misses.remember("v1", 1_000);
        misses.forget("v1");
        assert!(!misses.held("v1", 1_500, 60_000));
    }

    fn ids(list: &[&str]) -> Vec<String> {
        list.iter().map(|id| id.to_string()).collect()
    }

    #[test]
    fn the_warm_up_queue_asks_for_each_video_once() {
        let warm = Prefetch::default();
        warm.push(&ids(&["a", "b", "a", ""]));
        assert_eq!(warm.len(), 2, "an empty id is not a video");
        warm.push(&ids(&["b", "c"]));
        assert_eq!(warm.len(), 3);
        assert_eq!(warm.next().as_deref(), Some("a"));
        assert_eq!(warm.next().as_deref(), Some("b"));
        assert_eq!(warm.next().as_deref(), Some("c"));
        assert_eq!(warm.next(), None);
        assert!(warm.is_empty());
    }

    #[test]
    fn a_queue_that_overflows_drops_the_page_that_scrolled_away() {
        let warm = Prefetch::default();
        let many: Vec<String> = (0..PREFETCH_CAP + 5)
            .map(|i| format!("v{i}"))
            .collect();
        warm.push(&many);
        assert_eq!(warm.len(), PREFETCH_CAP);
        // The newest push is the screen the viewer is actually looking at.
        assert_eq!(warm.next().as_deref(), Some("v5"));
        warm.requeue("v5");
        assert_eq!(warm.next().as_deref(), Some("v5"));
        // A video that is still waiting is not put in the queue twice.
        warm.requeue("v6");
        assert_eq!(warm.len(), PREFETCH_CAP - 1, "requeue does not duplicate");
    }

    #[test]
    fn only_one_drainer_at_a_time() {
        let warm = Prefetch::default();
        assert!(warm.claim(), "the first caller starts the worker");
        assert!(!warm.claim(), "a second push must not start another");
        warm.release();
        assert!(warm.claim());
    }

    #[test]
    fn a_run_in_flight_is_visible_to_the_warm_up_queue() {
        // The background warm-up asks this before starting anything: joining a
        // run the viewer is already waiting for is fine, starting a second one
        // beside it is what made a click slower than not warming at all.
        use std::sync::mpsc::channel;
        let flights: SingleFlight<u32> = SingleFlight::new();
        let (started_tx, started_rx) = channel::<()>();
        let (finish_tx, finish_rx) = channel::<()>();
        std::thread::scope(|scope| {
            let flights = &flights;
            let handle = scope.spawn(move || {
                flights.run("watching", || {
                    started_tx.send(()).unwrap();
                    finish_rx.recv().unwrap();
                    Ok(1)
                })
            });
            started_rx.recv().unwrap();
            assert_eq!(flights.active(), 1, "the viewer is waiting on this one");
            finish_tx.send(()).unwrap();
            assert_eq!(handle.join().unwrap().unwrap(), 1);
            assert_eq!(flights.active(), 0, "released as soon as it is over");
        });
    }
}
