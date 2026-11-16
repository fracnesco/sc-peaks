// Symlink this repository into the SuperCollider extensions folder.
// Evaluate the block below, then recompile the class library
// (Language -> Recompile Class Library, or ctrl/cmd + shift + L).
//
// This is the route for a clone you want to keep editing. If you would
// rather have it installed and updated for you, do not use this file -
// install it as a quark instead, from a fresh sclang:
//
//   Quarks.install("https://github.com/fracnesco/sc-peaks.git");
//   thisProcess.recompile;
//
// One or the other, not both: two copies on the class path is a
// duplicate class error at compile time.
//
// ln -s is macOS and Linux. On Windows, use the quark, or copy this
// folder into Platform.userExtensionDir by hand - .openOS opens it.
//
// Peaks (Shear and Wall) is built on sc fx kit (../sc fx kit), which is built on ParaEQ
// (../sc EQ), so both have to be installed too. If they are sitting next
// to this folder, this script links them as well.

(
var link, src, parent, dst;

src = PathName(thisProcess.nowExecutingPath).pathOnly;
if (src.endsWith("/")) { src = src.drop(-1) };			// ln -s dislikes it
parent = PathName(src).parentPath;
dst = Platform.userExtensionDir +/+ "Peaks";

link = { |from, to, what, done|
	if (from.endsWith("/")) { from = from.drop(-1) };
	case
	{ File.exists(to) } {
		("Peaks: % is already installed at %.".format(what, to)).postln;
		done.value;
	}
	{ File.exists(from).not } {
		("Peaks: % is not installed, and there is no copy of it at %."
			"\n  The class library will not compile without it."
			"\n  Install it first, then run this again."
			.format(what, from)).warn;
	}
	{ true } {
		File.mkdir(Platform.userExtensionDir);
		("ln -s" + from.shellQuote + to.shellQuote).unixCmd({ |exit|
			if (exit == 0) {
				"Peaks: linked % -> %".format(what, to).postln;
				done.value;
			} {
				"Peaks: linking % failed with exit code %".format(what, exit).warn;
			};
		});
	};
};

// what it is built on first - without them the class library will not compile
link.value(parent +/+ "sc EQ", Platform.userExtensionDir +/+ "ParaEQ", "ParaEQ", {
	link.value(parent +/+ "sc fx kit", Platform.userExtensionDir +/+ "FxKit", "FxKit", {
		link.value(src, dst, "Peaks", {
			"".postln;
			"Now recompile the class library: ctrl/cmd + shift + L".postln;
		});
	});
});
)


// ---------------------------------------------------------------- verify
(
var missing = [\PeakKit, \Shear, \ShearView, \Wall, \WallView]
	.select { |sym| sym.asClass.isNil };

if (missing.notEmpty) {
	("Peaks is not compiled - % is missing."
		"\n  Recompile the class library, ctrl/cmd + shift + L, and check the"
		"\n  post window for compile errors - a missing ParaEQ or FxKit is the usual reason."
		.format(missing.join(", "))).warn;
} {
	"Peaks is installed.".postln;
	[\Shear, \Wall].do { |name|
		var cls = name.asClass;
		"  % presets: % - %".format(name, cls.presetNames.size, cls.presetNames.join(", ")).postln;
	};
	"  in a graph:  Shear.ar(sig, \\shear)      then  Shear(Ndef(\\x), \\shear).gui".postln;
	"  on a bus:    Wall.onBus(0, 2).enabled_(true).gui".postln;
	"  an insert:   ~sdg.addInsert(0, \\shear)  or  ~sdg.addInsert(0, \\wall)".postln;
	"  help:        Shear or Wall, then cmd+d".postln;
};
)


// ------------------------------------------------------------- uninstall
(
var link = Platform.userExtensionDir +/+ "Peaks";
case
{ File.type(link) == \symlink } {
	File.delete(link);
	("Removed %."
		"\n  Recompile the class library to finish.".format(link)).postln;
}
{ File.type(link) == \not_found } {
	"Nothing to remove at %.".format(link).postln;
}
{ true } {
	"% is not a link, leaving it alone.".format(link).warn;
};
)
