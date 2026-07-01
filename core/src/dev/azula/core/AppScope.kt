package dev.azula.core

/**
 * Marker type for the app-wide Metro dependency-injection scope — one flat graph,
 * one instance per process. Lives in `core` so every module (feature graphs and
 * contributed bindings alike) can reference the same scope.
 */
abstract class AppScope private constructor()
