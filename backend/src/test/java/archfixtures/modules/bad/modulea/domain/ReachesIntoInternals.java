package archfixtures.modules.bad.modulea.domain;

import archfixtures.modules.bad.moduleb.domain.Internal;

/** Violation: module a uses module b's internal domain class. */
public class ReachesIntoInternals {
    Internal internal;
}
