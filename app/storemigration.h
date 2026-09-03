#pragma once

// ArtMoon 1.2.0 renamed the settings store from FoggyBytes/StreamLight (the
// fork's original working title) to ArtMoon/ArtMoon. This is the migration
// that makes that rename invisible to users: on first launch against the new
// store, every key from the old one is copied across verbatim, then the store
// is version-stamped so the copy happens exactly once.
//
// Rules, all of which are load-bearing:
//   · The old store is READ-ONLY. It is never written, never deleted, never
//     marked. A downgrade to 1.1.x must find it exactly as it was left.
//   · A crash mid-migration is safe: nothing is stamped until the copy
//     completes, so the next launch simply re-copies over the partial result.
//   · Idempotent by construction — the version stamp is the latch.
//
// Called from main.cpp after the portable-mode format/path decision and
// before StoreReset::probe(), so the probe sees an already-stamped store and
// never shows the "settings were reset" notice for a rename that preserved
// everything.
namespace StoreMigration
{
    void run();
}
