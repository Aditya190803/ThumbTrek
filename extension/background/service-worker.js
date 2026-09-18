/**
 * Chrome entry point. A module that pulls in background/shared.js, which
 * holds every handler — see that file for why the logic lives there: the
 * same file runs as a classic background script on Firefox, where service
 * workers are not available.
 */
import './shared.js';
