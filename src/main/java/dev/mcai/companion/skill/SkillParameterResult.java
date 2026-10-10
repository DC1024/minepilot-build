package dev.mcai.companion.skill;

import java.util.Objects;
import java.util.Optional;

public sealed interface SkillParameterResult<P>
        permits SkillParameterResult.Valid, SkillParameterResult.Invalid {
    Optional<P> value();

    Optional<SkillFailure> failure();

    static <P> SkillParameterResult<P> valid(P value) {
        return new Valid<>(value);
    }

    static <P> SkillParameterResult<P> invalid(String failureCode) {
        return new Invalid<>(SkillFailure.of(failureCode));
    }

    record Valid<P>(P parsedValue) implements SkillParameterResult<P> {
        public Valid {
            Objects.requireNonNull(parsedValue, "parsedValue");
        }

        @Override
        public Optional<P> value() {
            return Optional.of(parsedValue);
        }

        @Override
        public Optional<SkillFailure> failure() {
            return Optional.empty();
        }
    }

    record Invalid<P>(SkillFailure rejection) implements SkillParameterResult<P> {
        public Invalid {
            Objects.requireNonNull(rejection, "rejection");
        }

        @Override
        public Optional<P> value() {
            return Optional.empty();
        }

        @Override
        public Optional<SkillFailure> failure() {
            return Optional.of(rejection);
        }
    }
}
