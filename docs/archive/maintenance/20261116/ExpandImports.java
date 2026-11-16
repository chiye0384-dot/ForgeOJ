import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

// One-off compiler-assisted maintenance, retained with this task's local receipts.
public class ExpandImports {
  record Star(ImportTree tree, String owner, boolean isStatic, Set<? extends Element> members) {}
  record Edit(int start, int end, String replacement) {}
  record Change(Path file, String original, String updated, int stars) {}
  static final List<String> MODULES = List.of("forgeoj-api", "forgeoj-judge-worker");

  static String sha(byte[] bytes) throws Exception {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  static List<Change> compile(Path repo, Path out, boolean expand) throws Exception {
    List<Change> changes = new ArrayList<>();
    for (String module : MODULES) {
      var compiler = ToolProvider.getSystemJavaCompiler();
      var diagnostics = new DiagnosticCollector<JavaFileObject>();
      Path dest = out.resolve(module);
      Files.createDirectories(dest);
      String cp = Files.readString(repo.resolve(module + "/target/import-cleanup-classpath.txt")).trim();
      List<Path> sources;
      try (var files = Files.walk(repo.resolve(module + "/src"))) {
        sources = files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
      }
      try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
        var task = (JavacTask) compiler.getTask(new PrintWriter(System.err), manager, diagnostics,
            List.of("--release", "21", "-proc:none", "-g:none", "-parameters", "-classpath", cp,
                "-d", dest.toString()), null, manager.getJavaFileObjectsFromPaths(sources));
        List<CompilationUnitTree> units = new ArrayList<>();
        task.parse().forEach(units::add);
        task.analyze();
        var errors = diagnostics.getDiagnostics().stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList();
        if (!errors.isEmpty()) throw new IllegalStateException(module + " attribution failed: " + errors);
        Trees trees = Trees.instance(task);
        var elements = task.getElements();
        if (expand) for (var unit : units) {
          List<Star> stars = new ArrayList<>();
          for (var imp : unit.getImports()) {
            String name = imp.getQualifiedIdentifier().toString();
            if (!name.endsWith(".*")) continue;
            String owner = name.substring(0, name.length() - 2);
            TypeElement type = elements.getTypeElement(owner);
            Set<? extends Element> members = type == null ? Set.of() : new HashSet<>(elements.getAllMembers(type));
            stars.add(new Star(imp, owner, imp.isStatic(), members));
          }
          if (stars.isEmpty()) continue;
          Map<Star, Set<String>> imports = new HashMap<>();
          stars.forEach(s -> imports.put(s, new TreeSet<>()));
          new TreePathScanner<Void, Void>() {
            @Override public Void visitIdentifier(IdentifierTree node, Void unused) {
              Element e = trees.getElement(getCurrentPath());
              String simple = node.getName().toString();
              for (Star star : stars) {
                boolean used = e instanceof TypeElement type && !star.isStatic()
                    && (type.getQualifiedName().contentEquals(star.owner() + "." + simple)
                        || star.members().contains(e));
                used |= e != null && star.isStatic() && e.getModifiers().contains(Modifier.STATIC)
                    && star.members().contains(e);
                if (used) imports.get(star).add("import " + (star.isStatic() ? "static " : "")
                    + star.owner() + "." + simple + ";");
              }
              return super.visitIdentifier(node, unused);
            }
          }.scan(unit, null);
          Path file = Path.of(unit.getSourceFile().toUri());
          String original = Files.readString(file);
          List<Edit> edits = new ArrayList<>();
          for (Star star : stars) edits.add(new Edit(
              Math.toIntExact(trees.getSourcePositions().getStartPosition(unit, star.tree())),
              Math.toIntExact(trees.getSourcePositions().getEndPosition(unit, star.tree())),
              String.join("\n", imports.get(star))));
          edits.sort(Comparator.comparingInt(Edit::start).reversed());
          StringBuilder updated = new StringBuilder(original);
          for (Edit edit : edits) updated.replace(edit.start(), edit.end(), edit.replacement());
          changes.add(new Change(file, original, updated.toString(), stars.size()));
        }
        task.generate();
        if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR))
          throw new IllegalStateException("Class generation failed for " + module);
        System.out.println(module + ": attributed and generated " + sources.size() + " source files");
      }
    }
    return changes;
  }

  static Map<String, String> classes(Path root) throws Exception {
    Map<String, String> hashes = new TreeMap<>();
    try (var files = Files.walk(root)) {
      for (Path p : files.filter(f -> f.toString().endsWith(".class")).toList())
        hashes.put(root.relativize(p).toString(), sha(Files.readAllBytes(p)));
    }
    return hashes;
  }

  public static void main(String[] args) throws Exception {
    Path repo = Path.of(args[0]).toAbsolutePath();
    Path unit = Path.of(args[1]).toAbsolutePath();
    if (args.length == 3 && args[2].equals("--verify")) {
      compile(repo, unit.resolve("after-classes"), false);
      var before = classes(unit.resolve("before-classes"));
      var after = classes(unit.resolve("after-classes"));
      if (before.isEmpty() || !before.equals(after)) {
        Set<String> changed = new TreeSet<>(before.keySet());
        changed.addAll(after.keySet());
        changed.removeIf(k -> java.util.Objects.equals(before.get(k), after.get(k)));
        throw new IllegalStateException("Generated bytecode differs: " + changed);
      }
      Files.writeString(unit.resolve("bytecode-proof.txt"), "IDENTICAL " + before.size() + " classes; javac --release 21 -proc:none -g:none -parameters\n");
      System.out.println("IDENTICAL: " + before.size() + " generated classes");
      return;
    }
    if (Files.exists(unit.resolve("before-classes"))) throw new IllegalStateException("Do not overwrite prior baseline");
    List<Change> changes = compile(repo, unit.resolve("before-classes"), true);
    for (Change change : changes)
      if (!Files.readString(change.file()).equals(change.original())) throw new IllegalStateException("Source changed during analysis");
    List<String> receipt = new ArrayList<>();
    for (Change change : changes) {
      Path relative = repo.relativize(change.file());
      Path backup = unit.resolve("before-sources").resolve(relative);
      Files.createDirectories(backup.getParent());
      Files.writeString(backup, change.original());
      Files.writeString(change.file(), change.updated());
      receipt.add(relative + "\t" + sha(change.original().getBytes(StandardCharsets.UTF_8))
          + "\t" + sha(change.updated().getBytes(StandardCharsets.UTF_8)) + "\t" + change.stars());
    }
    Files.write(unit.resolve("import-changes.tsv"), receipt);
    System.out.println("Expanded " + changes.stream().mapToInt(Change::stars).sum()
        + " wildcard imports in " + changes.size() + " files");
  }
}
