/*
 * Copyright 2024 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.maven;

import lombok.Value;
import lombok.With;
import org.openrewrite.internal.ExceptionUtils;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.marker.Markup;
import org.openrewrite.maven.tree.GroupArtifact;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.Scope;
import org.openrewrite.xml.tree.Xml;

import java.util.*;
import java.util.stream.Collectors;

@Value
@With
public class MavenDownloadingFailures {

    List<MavenDownloadingFailure> failures;

    public static MavenDownloadingFailures append(
            @Nullable MavenDownloadingFailures current,
            MavenDownloadingFailure failure) {
        if (current == null) {
            current = new MavenDownloadingFailures(Collections.emptyList());
        }

        return current.withFailures(ListUtils.concat(current.getFailures(), failure));
    }

    public static MavenDownloadingFailures append(
            @Nullable MavenDownloadingFailures current,
            @Nullable MavenDownloadingFailures exceptions) {
        if (current == null) {
            if (exceptions == null) {
                return new MavenDownloadingFailures(Collections.emptyList());
            }
            current = new MavenDownloadingFailures(Collections.emptyList());
        }
        if (exceptions == null) {
            return current;
        }
        return current.withFailures(ListUtils.concatAll(current.getFailures(), exceptions.getFailures()));
    }

    public Xml.Document warn(Xml.Document document) {
        if(failures.isEmpty()) {
            return document;
        }
        MavenResolutionResult mrr = document.getMarkers().findFirst(MavenResolutionResult.class).orElse(null);
        if(mrr == null) {
            return Markup.warn(document, "No maven resolution result is available", "");
        }

        Map<GroupArtifact, List<MavenDownloadingFailure>> byGav = new HashMap<>();
        for (MavenDownloadingFailure failure : failures) {
            byGav.computeIfAbsent(new GroupArtifact(failure.getFailedOn().getGroupId(),
                    failure.getFailedOn().getArtifactId()), ga -> new ArrayList<>()).add(failure);
        }

        return (Xml.Document) new MavenIsoVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer integer) {
                Xml.Tag t = super.visitTag(tag, integer);
                if(!(DEPENDENCY_MATCHER.matches(getCursor()) || MANAGED_DEPENDENCY_MATCHER.matches(getCursor()) || PARENT_MATCHER.matches(getCursor()))
                   || !t.getChildValue("groupId").isPresent() || !t.getChildValue("artifactId").isPresent()) {
                    return t;
                }
                String groupId = t.getChildValue("groupId").get();
                String artifactId = t.getChildValue("artifactId").get();
                Scope scope = Scope.fromName(t.getChildValue("scope").orElse("compile"));

                for (GroupArtifact ga : byGav.keySet()) {
                    if (ga.getGroupId().equals(groupId) && ga.getArtifactId().equals(artifactId)
                        || mrr.findDependencies(groupId, artifactId, scope)
                                .stream()
                                .anyMatch(it -> it.getDependencies().stream()
                                        .anyMatch(transitive ->
                                                transitive.getGroupId().equals(ga.getGroupId()) && transitive.getArtifactId().equals(ga.getArtifactId())))) {
                        // Skip uninteresting exceptions if those are the only exceptions available
                        List<MavenDownloadingFailure> withoutRetries = byGav.get(ga).stream()
                                .filter(it -> !it.getMessage().contains("Did not attempt to download because of a previous failure to retrieve from this repository"))
                                .collect(Collectors.toList());
                        List<MavenDownloadingFailure> exceptionsToAdd;
                        if(!withoutRetries.isEmpty()) {
                            exceptionsToAdd = withoutRetries;
                        } else {
                            exceptionsToAdd = byGav.get(ga);
                        }
                        for (MavenDownloadingFailure failure : exceptionsToAdd) {
                            t = Markup.warn(t, failure.getMessage(), ExceptionUtils.sanitizeStackTrace(failure.getStackTrace()));
                        }
                    }
                }
                return super.visitTag(t, integer);
            }
        }.visitNonNull(document, 0);
    }
}
