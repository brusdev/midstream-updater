package com.redhat.midstream.updater;

public enum CommitState {
   TODO,
   DONE, // cherry-pick eseguito // verificare stato downstream issues // si può solo fare il revert e tornare a TODO
   INCOMPLETE, // cherry-pick eseguito ma lo stato e/o le labels delle downstream issues non sono valide // aggiornare le downstream issue oppure fare il revert e tornare a TODO
   BLOCKED, // cherry-pick non eseguito perchè il commit è relativo ad un upstream bug issue ma non esiste la relativa downstream issue oppure è esiste la downstream issue ma la target release non corrisponde // clonare issue da upstream issue o downstream issues oppure aggiungere alla downstream issue con NO_BACKPORT_NEEDED label per ignorarej
   SKIPPED, // rimuove NO_BACKPORT_NEEDED
   FAILED,
}
