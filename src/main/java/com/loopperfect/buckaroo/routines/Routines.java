package com.loopperfect.buckaroo.routines;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.google.gson.JsonParseException;
import com.loopperfect.buckaroo.*;
import com.loopperfect.buckaroo.io.IO;
import com.loopperfect.buckaroo.io.IOContext;
import com.loopperfect.buckaroo.serialization.Serializers;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.stream.Collectors;

public final class Routines {

    private Routines() {

    }

    private static final Either<IOException, Recipe> readRecipe(final IOContext context, final Path path) {
        Preconditions.checkNotNull(path);
        Preconditions.checkNotNull(context);
        return context.readFile(path).join(
                x -> Either.left(x),
                x -> {
                    try {
                        return Either.right(Serializers.gson().fromJson(x, Recipe.class));
                    } catch (final JsonParseException e) {
                        return Either.left(new IOException(e));
                    }
                });
    }

    // TODO: Refactor to make this more functional!
    private static IO<Either<IOException, ImmutableList<Either<IOException, Recipe>>>> loadRecipes = context -> {
        Preconditions.checkNotNull(context);
        final Path recipesDirectory = Paths.get(context.getUserHomeDirectory().toString(), ".buckaroo/recipes");
        final Either<IOException, ImmutableList<Path>> listFiles = context.listFiles(recipesDirectory);
        if (listFiles.isLeft()) {
            return Either.left(listFiles.getLeft());
        }
        return Either.right(ImmutableList.copyOf(listFiles.getRight()
                .stream()
                .filter(x -> isJsonFile(x))
                .map(x -> x.normalize())
                .distinct()
                .map(x -> readRecipe(context, x))
                .collect(Collectors.toList())));
    };

    private static Path recipesPath(final IOContext context) {
        Preconditions.checkNotNull(context);
        return Paths.get(context.getUserHomeDirectory().toString(), ".buckaroo/recipes");
    }

    private static String formatRecipe(final Recipe recipe) {
        Preconditions.checkNotNull(recipe);
        return recipe.name.name +
                " " +
                recipe.versions.keySet().stream()
                        .map(u -> u.toString()).collect(Collectors.joining(", "));
    }

    private static boolean isJsonFile(final Path path) {
        Preconditions.checkNotNull(path);
        return Files.getFileExtension(path.toString()).equalsIgnoreCase("json");
    }

    public static final IO<Unit> listRecipes = IO.of(context -> Paths.get(context.getUserHomeDirectory().toString(), ".buckaroo/recipes"))
        .flatMap(x -> IO.println("Searching for recipes in " + x + "... ").then(IO.value(x)))
        .flatMap(x -> IO.listFiles(x))
        .flatMap(x -> context -> x.map(
                y -> y.toString(),
                y -> y.stream()
                        .filter(z -> Files.getFileExtension(z.toString()).equalsIgnoreCase("json"))
                        .distinct()
                        .map(z -> context.readFile(z)
                                .rightProjection(w -> Serializers.gson().fromJson(w, Recipe.class))
                                .join(w -> "Could not load " + z.getFileName(), w -> formatRecipe(w)))
                        .collect(Collectors.joining("\n"))))
        .flatMap(x -> x.join(y -> IO.println(y), y -> IO.println(y)));

    private static final String invalidProjectNameWarning =
            "A project name may only contain letters, numbers, underscores and dashes. " +
                    "It must be between 3 and 30 characters. ";

    private static final IO<Identifier> requestIdentifier = IO.read()
            .map(x -> x.flatMap(y -> Identifier.parse(y)))
            .flatMap(x -> x.isPresent() ? IO.value(x) : IO.println(invalidProjectNameWarning).then(IO.value(x)))
            .until(x -> x.isPresent())
            .map(x -> x.get());

    // TODO: Get license
    public static final IO<Unit> createProjectSkeleton = IO.of(context -> context.getWorkingDirectory())
            .flatMap(x -> IO.println("Creating a project in " + x + "... "))
            .flatMap(x -> IO.println("What is the name of your project? ").then(IO.value(x)))
            .then(requestIdentifier)
            .flatMap(x -> IO.println(x).then(IO.value(x)))
            .map(x -> Project.of(x, Optional.empty(), ImmutableMap.of()))
            .flatMap(x -> IO.println("Creating the buckaroo.json file... ").then(IO.value(x)))
            .map(x -> Serializers.gson(true).toJson(x))
            .flatMap(x -> IO.writeFile(Paths.get("buckaroo.json"), x))
            .flatMap(x -> x.isPresent() ?
                    IO.println(x.get()) :
                    IO.println("Done! ")
                            .then(IO.println("Creating the modules directory... "))
                            .then(IO.createDirectory(Paths.get("./buckaroo")))
                            .flatMap(y -> y.isPresent() ?
                                    IO.println(y.get()) :
                                    IO.println("Done!")
                                            .then(IO.println("Make sure you add buckaroo.json and buckaroo/ to your .gitignore"))));

    private static final IO<Either<IOException, Project>> readProjectFile =
            IO.readFile(Paths.get("buckaroo.json"))
                    .map(x -> x.join(
                            y -> Either.left(y),
                            y -> {
                                try {
                                    return Either.right(Serializers.gson().fromJson(y, Project.class));
                                } catch (final JsonParseException e) {
                                    return Either.left(new IOException(e));
                                }
                            }));

    private static final IO<Optional<IOException>> writeProjectFile(final Project project) {
            return context -> context.writeFile(
                    Paths.get("buckaroo.json"),
                    Serializers.gson(true).toJson(project),
                    true);
    }

    // TODO: Do we want to modify the BUCK file?
    // We need to do a recursive dependency search!
    public static IO<Unit> installDependency(final Identifier projectToInstall, final Optional<SemanticVersionRequirement> versionRequirement) {
        Preconditions.checkNotNull(projectToInstall);
        Preconditions.checkNotNull(versionRequirement);
        // First read the buckaroo.json file
        return readProjectFile
                .flatMap(x -> x.join(
                        // Print out any error
                        error -> IO.println(error.toString()),
                        // Read the project file...
                        project -> {
                            // Is it already installed?
                            if (project.dependencies.containsKey(projectToInstall)) {
                                // Yes
                                return IO.println("That library is already a dependency! Version " +
                                        project.dependencies.get(projectToInstall).encode());
                            }
                            // No, so load the recipes...
                            return loadRecipes.flatMap(y -> y.join(
                                    // Loading the recipes failed; print the error
                                    error -> IO.println(error),
                                    // Success!
                                    recipes -> {
                                        final ImmutableSet<SemanticVersion> versions =
                                                recipes.stream()
                                                        .filter(i -> i.join(
                                                                j -> false,
                                                                j -> j.name.equals(projectToInstall)
                                                        ))
                                                        .findAny()
                                                        .flatMap(i -> i.rightProjection(j -> j.versions.keySet()).toOptional())
                                                        .orElseGet(() -> ImmutableSet.of());
                                        final SemanticVersionRequirement versionToFind =
                                                versionRequirement.orElse(AnySemanticVersion.of());
                                        final Optional<SemanticVersion> versionToUse =
                                                SemanticVersions.resolve(versions, ImmutableSet.of(versionToFind));
                                        // Did we find a version to use?
                                        if (!versionToUse.isPresent()) {
                                            // Nope
                                            return IO.println("Not version of " + projectToInstall +
                                                    " that satisfies " + versionToFind.encode() + " could be found. " +
                                                    "Perhaps you should run buckaroo upgrade? ");
                                        }
                                        // Yes!
                                        final Dependency dependency = Dependency.of(
                                                projectToInstall,
                                                versionRequirement.orElseGet(
                                                        () -> ExactSemanticVersion.of(versionToUse.get())));
                                        final Project modifiedProject = project.addDependency(dependency);
                                        // Write the project file; print done or error
                                        return IO.println("Installing " + projectToInstall.name + "@" + versionToUse.get() + "... ")
                                                .then(writeProjectFile(modifiedProject))
                                                .flatMap(i -> IO.println(i.map(j -> j.toString()).orElse("Done!")));
                                    }
                            ));
                        }));

    }
}
