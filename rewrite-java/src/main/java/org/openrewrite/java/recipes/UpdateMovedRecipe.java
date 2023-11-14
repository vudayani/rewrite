/*
 * Copyright 2023 the original author or authors.
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
package org.openrewrite.java.recipes;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.Option;
import org.openrewrite.Recipe;

import java.util.Arrays;
import java.util.List;

@Value
@EqualsAndHashCode(callSuper = false)
public class UpdateMovedRecipe extends Recipe {
    @Option(displayName = "The fully qualified className of recipe moved from",
        description = "The old fully qualified className of recipe before being moved.",
        example = "org.openrewrite.java.cleanup.UnnecessaryCatch")
    String oldRecipeFullyQualifiedClassName;

    @Option(displayName = "The fully qualified className of recipe moved to",
        description = "The new fully qualified className of recipe after being moved.",
        example = "org.openrewrite.staticanalysis.UnnecessaryCatch")
    String newRecipeFullyQualifiedClassName;

    @Override
    public String getDisplayName() {
        return "Update recipe location";
    }

    @Override
    public String getDescription() {
        return "Update all references to a recipe that has moved from one location to another. For instance, if a recipe moves from "
            + "`org.openrewrite.java.Recipe` to `org.openrewrite.java.migrate.Recipe`, this would update all references to point to the new "
            + "location.";
    }

    @Override
    public List<Recipe> getRecipeList() {
        return Arrays.asList(
            new UpdateMovedPackageClassName(oldRecipeFullyQualifiedClassName,newRecipeFullyQualifiedClassName),
            new UpdateMovedRecipeYaml(oldRecipeFullyQualifiedClassName, newRecipeFullyQualifiedClassName),
            new UpdateMovedRecipeXml(oldRecipeFullyQualifiedClassName, newRecipeFullyQualifiedClassName)
        );
    }
}
