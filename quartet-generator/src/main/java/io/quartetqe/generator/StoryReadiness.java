package io.quartetqe.generator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Shift-left check: raises questions about a story before any test is written. Only a story with no criteria stops work. */
public final class StoryReadiness {
    private static final Pattern VAGUE = Pattern.compile("(?i)\\b(fast|quickly|user[- ]friendly|intuitive|appropriate|properly|correctly|easy|seamless|etc\\.?|should work|as expected)\\b");
    private static final Pattern NEGATIVE = Pattern.compile("(?i)\\b(reject|rejected|error|invalid|insufficient|fail|fails|denied|not|cannot|unauthori[sz]ed|conflict)\\b");

    private StoryReadiness() {}

    public static List<Finding> check(WorkItem story, Principles principles) {
        List<Finding> out = new ArrayList<>();
        if (story.acceptanceCriteria().isEmpty()) {
            principles.finding("READY-CRITERIA", story.id(), "The story has no acceptance criteria.").ifPresent(out::add);
            return out;
        }
        if (!story.description().toLowerCase().contains("so that")) {
            principles.finding("READY-VALUE", story.id(), "State the business value with 'so that ...'.").ifPresent(out::add);
        }
        boolean negative = false;
        for (var criterion : story.acceptanceCriteria().entrySet()) {
            var vague = VAGUE.matcher(criterion.getValue());
            if (vague.find()) {
                principles.finding("READY-MEASURABLE", criterion.getKey(), "'" + vague.group() + "' cannot be tested. State a measurable outcome.").ifPresent(out::add);
            }
            negative |= NEGATIVE.matcher(criterion.getValue()).find();
        }
        if (!negative) principles.finding("READY-NEGATIVE", story.id(), "No failure or rejection path is described.").ifPresent(out::add);
        return out;
    }
}
