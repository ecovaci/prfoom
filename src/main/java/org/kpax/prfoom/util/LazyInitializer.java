package org.kpax.prfoom.util;

import java.util.Optional;

/**
 * @author Eugen Covaci {@literal eugen.covaci.q@gmail.com}
 * Created on 11/16/2019
 */
@FunctionalInterface
public interface LazyInitializer<T> {

    T supplier();

    default  T createIfNull (T t) {
        synchronized (this) {
            return Optional.of(t).orElse(supplier());
        }
    }
}
