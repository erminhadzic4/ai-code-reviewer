package com.codereviewer.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileFilterTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"src/main/java/com/example/App.java",
			"main.py",
			"lib/parser.cpp",
			"include/parser.h",
			"Dockerfile",
			"Makefile",
			"CMakeLists.txt.in",
			".gitignore",
			".github/workflows/ci.yml",
			"config/application.yaml",
			"db/migration/V1__init.sql",
			"package.json",
			"tsconfig.json",
			// Names that only resemble an excluded directory.
			"src/builder/Planner.java",
			"src/targets/Linux.java",
			"distribution.js",
			"src/outbound/Sender.java",
			"cmake/FindFoo.cmake",
			"dependencies/versions.gradle",
			"externals.ts",
			"build.gradle",
			"target.c",
			// Source files whose name starts like a notice file.
			"license.py",
			"src/LicenseService.java",
			"notice.ts",
			"src/authors.rb",
			"src/Changelog.java",
			"LICENSES.java",
			// Names that only resemble a lock, minified or generated file.
			"src/lock.go",
			"src/minify.js",
			"src/map.ts",
			"src/generated.ts" })
	void reviewablePathsPass(String path) {
		assertThat(FileFilter.checkPath(path)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			".git/config",
			"node_modules/left-pad/index.js",
			"frontend/node_modules/react/index.js",
			"bower_components/jquery/jquery.js",
			"vendor/github.com/pkg/errors/errors.go",
			"backend/target/classes/App.java",
			"build/generated/Foo.java",
			"frontend/dist/app.js",
			"out/production/Main.java",
			".gradle/caches/x.properties",
			".idea/workspace.xml",
			".vscode/settings.json",
			"app/__pycache__/x.py",
			".venv/lib/site.py",
			"venv/lib/site.py",
			"coverage/lcov-report/index.html",
			".next/server/app.js",
			// C++ and shared directories.
			"cmake-build-debug/CMakeCache.cmake",
			"cmake-build-release/src/main.cpp",
			"Debug/main.cpp",
			"Release/main.cpp",
			"x64/Debug/main.cpp",
			".vs/config/applicationhost.config",
			"third_party/zlib/zlib.c",
			"external/fmt/format.cc",
			"deps/uv/src/uv.c",
			// Case does not matter, and neither does depth.
			"Node_Modules/x/index.js",
			"src/debug/Trace.java",
			"a/b/c/TARGET/d/E.java" })
	void filesInExcludedDirectoriesAreSkipped(String path) {
		assertThat(FileFilter.checkPath(path)).contains(SkipReason.EXCLUDED_DIRECTORY);
	}

	@Test
	void anExcludedNameIsOnlyADirectoryRule() {
		assertThat(FileFilter.checkPath("build")).isEmpty();
		assertThat(FileFilter.checkPath("scripts/release")).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"package-lock.json", "yarn.lock", "pnpm-lock.yaml", "bun.lockb", "Cargo.lock", "Gemfile.lock",
			"poetry.lock", "uv.lock", "Pipfile.lock", "composer.lock", "go.sum", "gradle.lockfile",
			"packages.lock.json", "frontend/package-lock.json" })
	void lockFilesAreSkipped(String path) {
		assertThat(FileFilter.checkPath(path)).contains(SkipReason.LOCK_FILE);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"LICENSE", "License", "license", "LICENCE", "UNLICENSE", "COPYING", "copying", "NOTICE",
			"AUTHORS", "CONTRIBUTORS", "PATENTS", "CHANGELOG",
			"LICENSE-MIT", "LICENSE_APACHE", "LICENSE.txt", "LICENSE.md", "COPYING.LESSER", "NOTICE.txt",
			"docs/LICENSE", "packages/core/LICENSE-MIT" })
	void licenceAndNoticeFilesAreSkipped(String path) {
		assertThat(FileFilter.checkPath(path)).contains(SkipReason.NON_CODE);
	}

	@ParameterizedTest
	@ValueSource(strings = { "README.md", "docs/guide.rst", "notes.txt", "data/users.csv", "assets/logo.svg",
			"README.MD" })
	void documentationAndDataFilesAreSkipped(String path) {
		assertThat(FileFilter.checkPath(path)).contains(SkipReason.NON_CODE);
	}

	@ParameterizedTest
	@ValueSource(strings = { "CMakeLists.txt", "src/core/CMakeLists.txt", "cmakelists.txt", "requirements.txt",
			"requirements-dev.txt", "requirements_test.txt", "backend/requirements.txt" })
	void buildFilesWithATextExtensionPass(String path) {
		assertThat(FileFilter.checkPath(path)).isEmpty();
	}

	@Test
	void buildFileInAnExcludedDirectoryIsStillSkipped() {
		assertThat(FileFilter.checkPath("build/CMakeLists.txt")).contains(SkipReason.EXCLUDED_DIRECTORY);
		assertThat(FileFilter.checkPath("venv/requirements.txt")).contains(SkipReason.EXCLUDED_DIRECTORY);
	}

	@Test
	void otherTextFilesAreStillSkipped() {
		assertThat(FileFilter.checkPath("notes.txt")).contains(SkipReason.NON_CODE);
		assertThat(FileFilter.checkPath("requirements.md")).contains(SkipReason.NON_CODE);
		assertThat(FileFilter.checkPath("CMakeLists.md")).contains(SkipReason.NON_CODE);
	}

	@ParameterizedTest
	@ValueSource(strings = { "logo.png", "photo.JPG", "font.woff2", "release.zip", "app.jar", "Main.class",
			"tool.exe", "libfoo.so", "module.wasm", "data.sqlite", "manual.pdf", "clip.mp4", "cache.pyc" })
	void binaryExtensionsAreSkipped(String path) {
		assertThat(FileFilter.checkPath(path)).contains(SkipReason.BINARY);
	}

	@ParameterizedTest
	@ValueSource(strings = { "app.min.js", "styles.min.css", "app.js.map", "vendor.bundle.js", "api.pb.go",
			"api_pb2.py", "model.g.dart", "Form1.Designer.cs", "schema.generated.ts", "jquery.MIN.js" })
	void minifiedAndGeneratedNamesAreSkipped(String path) {
		assertThat(FileFilter.checkPath(path)).contains(SkipReason.MINIFIED_OR_GENERATED);
	}

	@Test
	void ordinarySourcePasses() {
		String content = "public class App {\n\tpublic static void main(String[] args) {\n\t}\n}\n";

		assertThat(FileFilter.checkContent(content)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   ", "\n\n\t\n" })
	void emptyContentIsSkipped(String content) {
		assertThat(FileFilter.checkContent(content)).contains(SkipReason.EMPTY);
	}

	@Test
	void contentWithANulCharacterIsBinary() {
		assertThat(FileFilter.checkContent("abc\u0000def")).contains(SkipReason.BINARY);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"// Code generated by protoc-gen-go. DO NOT EDIT.\npackage api\n",
			"/* @generated */\nexport const a = 1;\n",
			"# This file is AUTO-GENERATED\nx = 1\n",
			"// autogenerated\nint x;\n",
			"<?php\n// This file was automatically generated by Composer\n",
			"#!/bin/sh\n\n\n\n# do not edit\n" })
	void generatedMarkerInTheFirstLinesIsSkipped(String content) {
		assertThat(FileFilter.checkContent(content)).contains(SkipReason.MINIFIED_OR_GENERATED);
	}

	@Test
	void generatedMarkerBelowTheFirstLinesIsIgnored() {
		String content = "line\n".repeat(FileFilter.GENERATED_MARKER_LINES) + "// DO NOT EDIT below this line\n";

		assertThat(FileFilter.checkContent(content)).isEmpty();
	}

	@Test
	void lineAtTheLimitPassesAndOneCharacterMoreIsMinified() {
		String padding = "short line\n".repeat(200);
		String atLimit = padding + "x".repeat(FileFilter.MAX_LINE_LENGTH) + "\n";
		String overLimit = padding + "x".repeat(FileFilter.MAX_LINE_LENGTH + 1) + "\n";

		assertThat(FileFilter.checkContent(atLimit)).isEmpty();
		assertThat(FileFilter.checkContent(overLimit)).contains(SkipReason.MINIFIED_OR_GENERATED);
	}

	@Test
	void longAverageLineIsMinified() {
		String content = ("x".repeat(FileFilter.MAX_AVERAGE_LINE_LENGTH + 1) + "\n").repeat(20);

		assertThat(FileFilter.checkContent(content)).contains(SkipReason.MINIFIED_OR_GENERATED);
	}

	@Test
	void averageLineAtTheLimitPasses() {
		String content = ("x".repeat(FileFilter.MAX_AVERAGE_LINE_LENGTH) + "\n").repeat(20);

		assertThat(FileFilter.checkContent(content)).isEmpty();
	}

	@Test
	void shortFileWithOneLongLineIsNotMinified() {
		String content = "x".repeat(FileFilter.MAX_AVERAGE_LINE_LENGTH + 50);

		assertThat(content.length()).isLessThan(FileFilter.MIN_LENGTH_FOR_AVERAGE);
		assertThat(FileFilter.checkContent(content)).isEmpty();
	}

}
