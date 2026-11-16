// Peaks - a walkthrough: Shear (a clipper) and Wall (a limiter).
// Evaluate one block at a time (cmd/ctrl + return inside the parentheses),
// never the whole file. Turn your speakers down before the first block.

// ---- 1. a bass and a drum loop to work on, Shear on each
(
s.waitForBoot {
	Ndef(\bass, {
		var f = Duty.kr(0.25, 0, Dseq([41, 41, 53, 41, 44, 41, 48, 39], inf)).midicps;
		var sig = Saw.ar(f * [1, 1.005]) * 0.4 + (SinOsc.ar(f) * 0.4);
		Shear.ar(RLPF.ar(sig, 900, 0.5), \shear)
	}).play;
	Ndef(\drums, {
		var t = Impulse.ar(4), bar = PulseCount.ar(t) % 8;
		var kick = SinOsc.ar(EnvGen.ar(Env.perc(0.001, 0.15), t * (bar % 4 < 1), 160, 45)) * Decay2.ar(t * (bar % 4 < 1), 0.001, 0.35);
		var snare = BPF.ar(WhiteNoise.ar, 1800, 0.7) * Decay2.ar(t * (bar % 4 > 1.5) * (bar % 4 < 2.5), 0.001, 0.18) * 1.5;
		var hat = HPF.ar(WhiteNoise.ar, 7000) * Decay2.ar(Impulse.ar(8), 0.001, 0.04) * 0.3;
		Shear.ar(Pan2.ar(kick + snare, 0) + Pan2.ar(hat, 0.3), \shear)
	}).play;
};
)

// ---- 2. the objects, and Shear's window on the bass
b = Shear(Ndef(\bass), \shear);
d = Shear(Ndef(\drums), \shear);
b.gui;

// ---- 3. drive and clip: the curve flattens at the clip level, match keeps the loudness
b.clip = -12; b.drive = 6;
b.drive = 18;              // more crunch, the same loudness
b.match = false;           // now louder as well
b.match = true;

// ---- 4. every mode on the bass, two seconds each (watch the dots ride the curve)
(
Routine {
	Shear.modes.do { |m| b.mode = m; m.postln; 2.wait };
	b.mode = \smooth;
}.play(AppClock);
)

// ---- 5. bend: each mode's own character
b.mode = \fold; b.bend = 0.2;
b.bend = 0.6; b.tone = 4500;     // deeper folding, the top tamed
b.mode = \wrap; b.bend = 0;      // a broken converter
b.bend = 1;                      // a triangle fold
b.mode = \diode; b.bend = 0.7; b.bias = 0.3;
b.preset = \default;

// ---- 6. the same on the drums
d.gui;
(
Routine {
	Shear.modes.do { |m| d.mode = m; m.postln; 2.wait };
	d.mode = \smooth;
}.play(AppClock);
)
d.preset = 'standard clip';      // a mastering clip: louder, no obvious distortion
d.detail = 0.6;                  // the highs of what was cut, given back
d.preset = 'sputter';            // gap: the quiet detail drops out

// ---- 7. delta: hear only what the clipper takes away
d.preset = 'standard clip';
d.delta = true;
d.delta = false;

// ---- 8. the two together, through Wall
(
Ndef(\bass).stop; Ndef(\drums).stop;      // they go on playing, only into the mix
Ndef(\mix, { Wall.ar(Ndef.ar(\bass, 2) + Ndef.ar(\drums, 2), \wall) }).play;
w = Wall(Ndef(\mix), \wall);
w.gui;
)

// ---- 9. push it into the ceiling
w.input = 6;
w.input = 12; w.style = \punchy;     // snappier, it pumps
w.style = \loud;                     // a clipper first, the limiter takes the rest
w.style = \clean; w.input = 6;

// ---- 10. delta: what the limiter takes away
w.delta = true;
w.delta = false;
w.preset = 'what it takes';          // input +9, delta on
w.preset = 'modern';

// ---- 11. lookahead: cleaner, and later (the caption says how many samples)
w.ahead = '6 ms';
w.ahead = 'none';                    // a clipper with a release: 6 samples late
w.ahead = '0.5 ms';
Wall.latency('0.5 ms', \clean, s.sampleRate);

// ---- 12. in SuperDirtGUI: inserts on an orbit (~sdg is SuperDirtGUI's object)
// ~sdg.addInsert(0, \shear);        // + insert on orbit 0, listed under dynamics
// ~sdg.addInsert(0, \wall);         // keep ahead at 0.5 ms on an orbit: SuperDirt
//                                   // has no delay compensation
// right click the insert -> listen to tidal, then in Tidal:
//   d1 $ s "shear*4" # mode "<smooth fold wrap>" # drive "0 18"
//   d2 $ s "wall" # limit (-3) # recovery 0.05

// ---- 13. done
(
w.free; b.free; d.free;
Ndef(\mix).clear; Ndef(\bass).clear; Ndef(\drums).clear;
)
