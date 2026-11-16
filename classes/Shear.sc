// =====================================================================
// Shear - a clipper: antialiased, gain-matched, with ten curves
//
// A clipper flattens whatever goes past a level. Shear says where that level
// is (`clip`, in dB), how hard the signal is pushed into it (`drive`) and
// how the corner is shaped (`mode`, and `bend` for each mode's own
// character): clean (smooth, crisp, inflate), colour (tube, sine, diode),
// creative (fold, wrap, gap, rectify). With `match` on (the default) the
// output stays at the input's loudness: the drive is taken back at once, and
// what the curve takes away is given back from a measured table (by mode,
// bend, how far over the clip level the sound goes and how peaky it is), so
// turning the drive up changes the character, not the loudness. It never
// turns up past the input. With `safe` on (the default) nothing leaves it
// above the clip level.
//
//   Ndef(\bass, { Shear.ar(Saw.ar([55, 55.3]) * 0.5, \shear) }).play;
//   c = Shear(Ndef(\bass), \shear);
//   c.gui;
//   c.drive = 18; c.mode = \tube;
//   c.mode = \fold; c.bend = 0.45; c.tone = 4500;
//   c.preset = 'germanium fuzz';
//   c.delta = true;                      // only what the clipper takes away
//
// The curves are antialiased (first-order ADAA in Vicanek's transparent
// form): below the clip level nothing at all happens to the signal but a
// first-order allpass, about one sample late, and the dry (`mix`, bypass)
// goes through the same allpass, so the two never comb.
//
// Around the curve: `bias` and `skew` (an offset along the curve, the same or
// opposite on the two sides: even harmonics, width), `heat` (the drive
// follows the level), `detail` (gives back the highs of what was cut),
// `slew` (dulls the loudest fast edges), `thump` (a low shelf before the
// curve and its mirror after), `tone` (a low pass over the result).
// =====================================================================

Shear : FxParamUnit {
	classvar <>eps = 0.02;				// the ADAA closeness threshold on |p - q|, in clip units: 0.02 measured best (float floor under -83 dB, t2 #4)
	classvar <>buildExtras = true;		// false: the same graph without the display's extras (a null test)
	classvar <>probe;					// an Event: *process leaves its insides there (checks only)
	classvar <>matchOverride;			// replaces matchTable while measuring it
	classvar <>matchTime = 0.3;			// seconds: the time constant of the level match follows

	*controlName { ^\shear }
	*displayClass { ^\ShearView.asClass }
	*guiClass { ^FxDeviceGUI }
	*panelClass { ^FxDevicePanel }
	*windowSize { ^Point(960, 580) }

	// FxUnit's latency: the antialiasing is about one sample late (PeakKit's
	// allpass, which the dry goes through too)
	latency {
		var srv = server ? Server.default;
		^1 / (srv.sampleRate ? srv.options.sampleRate ? 48000)
	}
	*insertCategory { ^\dynamics }
	*hasTail { ^false }
	*usesTidalClock { ^false }

	// dynamics' orchid: what was taken away
	*identityColour { ^FxPalette.identity(\dynamics) ?? { Color(0.92, 0.50, 0.96) } }

	// the meter's gain bar (R-FACE-6): the display's heroGain, wired in while the
	// device panel builds its header (meter and display exist by then)
	*panelExtras { |panel|
		var m = panel.tryPerform(\meter), d = panel.tryPerform(\display);
		if (m.isKindOf(FxMeter) and: { d.respondsTo(\heroGain) }) { m.gain = { d.heroGain } };
		^[]
	}

	*modes { ^PeakKit.curveNames }

	// extras: the highest |u| since the last reply, the cut in dB, heat's gain,
	// then 16 (lin, yu) pairs of the first channel (or the middle), in clip units
	*dotPairs { ^16 }
	*extrasSize { ^3 + (this.dotPairs * 2) }

	// match: the drive taken back at once, and what the curve takes away given
	// back from a table. The curve's loss (dB, input level minus output level)
	// depends on the mode, the bend, how far over the clip level the sound goes
	// (the input's RMS, followed, plus the drive minus the clip) and how peaky
	// it is (its crest: a follower's peak over its RMS, both over `matchTime`).
	// So a drive step is matched at once; only the input's own level and crest
	// are followed. Measured (fix/m_table2.scd: match off, drive 0, clip 0) on
	// a sine, pink noise and a drum loop, whose crests are `matchCrests`
	*matchLevels { ^(-24, -18 .. 60) }		// dB: the input's RMS plus drive minus clip
	*matchCrests { ^#[3.0, 9.96, 13.37] }	// dB: sine, pink, drums, as the followers read them

	// [mode][bend 0 .25 .5 .75 1][crest][level]
	*matchTable {
		^matchOverride ?? { this.prMatchTable }
	}

	// a crest in dB as an index into matchCrests (0 .. 2, clipped)
	*crestIndex { |c|
		var cs = this.matchCrests;
		^if (c <= cs[1]) { (c - cs[0]) / (cs[1] - cs[0]) } { 1 + ((c - cs[1]) / (cs[2] - cs[1])) }.clip(0, cs.size - 1)
	}

	// the curve's loss in dB at a level (dB over the clip level, RMS) and a
	// crest: the model of what match gives back, read as the server reads it
	*matchLoss { |mode = \smooth, bend = 0.3, levelDb = 0, crest = 9.96|
		var rows = this.matchTable[PeakKit.modeIndex(mode)], lv = this.matchLevels, top = lv.size - 1;
		var ci = this.crestIndex(crest);
		var vec = lv.size.collect { |j|
			rows.collect { |bRow| bRow.collect(_[j]).blendAt(ci) }.blendAt(bend.clip(0, 1) * (rows.size - 1))
		};
		var idx = ((levelDb - lv[0]) / (lv[1] - lv[0])).clip(0, top);
		^vec.blendAt(idx) + ((levelDb - lv.last).max(0) * ((vec[top] - vec[top - 1]) / (lv[1] - lv[0])))
	}

	// the gain match applies (dB, never above 0) for a steady input of rmsDb dBFS
	*matchGain { |rmsDb, drive = 6, clip = -6, mode = \smooth, bend = 0.3, crest = 9.96|
		^(drive.neg + this.matchLoss(mode, bend, rmsDb + drive - clip, crest)).min(0)
	}

	// Measured 2026-11-12 (fix/m_table2.scd). Each row then made never to
	// lose more at a lower level than at a higher one (gap's dead zone would
	// otherwise lift a quiet sound's remains by up to 18 dB)
	*prMatchTable {
		^#[
			[	// smooth
				[
					[0, 0, 0, 0, 1.66, 6.72, 12.34, 18.17, 24.08, 30.04, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0, 0, 0, 0.37, 2.94, 7.71, 13.68, 19.84, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0, 0.11, 1.13, 3.17, 6, 9.91, 14.62, 19.67, 25.2, 31.04, 37.09, 43.15, 49.27, 55.31, 61.32]
				],
				[
					[0, 0, 0, 0, 1.75, 6.74, 12.35, 18.17, 24.09, 30.04, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0, 0, 0, 0.44, 3, 7.73, 13.69, 19.84, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0, 0.14, 1.18, 3.21, 6.04, 9.94, 14.65, 19.7, 25.21, 31.04, 37.09, 43.15, 49.27, 55.31, 61.32]
				],
				[
					[0, 0, 0, 0.14, 2.01, 6.81, 12.38, 18.19, 24.09, 30.05, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0, 0, 0.04, 0.69, 3.2, 7.81, 13.72, 19.85, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0.01, 0.27, 1.35, 3.37, 6.21, 10.06, 14.74, 19.78, 25.24, 31.05, 37.1, 43.15, 49.27, 55.31, 61.32]
				],
				[
					[0, 0, 0.05, 0.63, 2.48, 6.92, 12.43, 18.21, 24.1, 30.05, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0, 0.01, 0.24, 1.15, 3.54, 7.95, 13.76, 19.86, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0.09, 0.52, 1.66, 3.68, 6.51, 10.27, 14.9, 19.9, 25.28, 31.07, 37.1, 43.15, 49.27, 55.31, 61.32]
				],
				[
					[0.17, 0.33, 0.68, 1.41, 3.07, 7.07, 12.49, 18.24, 24.12, 30.06, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0.22, 0.44, 0.9, 1.86, 4.01, 8.15, 13.82, 19.86, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0.55, 1.11, 2.25, 4.2, 6.95, 10.57, 15.11, 20.06, 25.35, 31.09, 37.11, 43.16, 49.27, 55.31, 61.32]
				]
			],
			[	// crisp
				[
					[0, 0, 0, 0, 1.71, 6.73, 12.35, 18.17, 24.08, 30.04, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0, 0, 0, 0.41, 2.97, 7.72, 13.69, 19.84, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0, 0.13, 1.16, 3.19, 6.02, 9.93, 14.63, 19.68, 25.2, 31.04, 37.09, 43.15, 49.27, 55.31, 61.32]
				],
				[
					[0, 0, 0, 0.13, 2.49, 6.91, 12.42, 18.21, 24.1, 30.05, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0, 0, 0.04, 0.92, 3.46, 7.93, 13.76, 19.85, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0, 0.37, 1.51, 3.55, 6.39, 10.21, 14.85, 19.89, 25.28, 31.06, 37.1, 43.15, 49.27, 55.31, 61.32]
				],
				[
					[0, 0, 0, 1.07, 3.79, 7.28, 12.57, 18.27, 24.13, 30.07, 36.04, 42.03, 48.03, 54.02, 60.02],
					[0, 0, 0.26, 1.79, 4.39, 8.36, 13.9, 19.88, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0.08, 0.77, 2.15, 4.22, 7.09, 10.78, 15.23, 20.24, 25.45, 31.12, 37.12, 43.16, 49.27, 55.31, 61.33]
				],
				[
					[0, 0, 0.03, 2.26, 5.12, 8.09, 12.8, 18.38, 24.18, 30.09, 36.05, 42.03, 48.03, 54.02, 60.02],
					[0, 0.03, 0.79, 2.86, 5.56, 9.02, 14.12, 19.91, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0.31, 1.31, 2.95, 5.12, 8.01, 11.53, 15.78, 20.67, 25.72, 31.21, 37.16, 43.18, 49.28, 55.32, 61.33]
				],
				[
					[0, 0, 0.87, 3.55, 6.48, 9.46, 13.17, 18.53, 24.25, 30.12, 36.06, 42.03, 48.03, 54.02, 60.02],
					[0, 0.2, 1.61, 4.06, 6.82, 9.89, 14.43, 19.97, 25.64, 31.47, 37.42, 43.42, 49.41, 55.4, 61.4],
					[0.68, 2, 3.85, 6.2, 9.08, 12.43, 16.47, 21.15, 26.09, 31.35, 37.21, 43.2, 49.29, 55.32, 61.33]
				]
			],
			[	// inflate
				[
					[-1.6, -1.6, -1.6, -1.6, 1.35, 6.6, 12.29, 18.14, 24.07, 30.04, 36.03, 42.03, 48.03, 54.02, 60.02],
					[-1.44, -1.44, -1.44, -0.74, 2.44, 7.52, 13.63, 19.84, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[-1.09, -1.05, 0.14, 2.34, 5.33, 9.54, 14.31, 19.52, 25.14, 31.01, 37.08, 43.14, 49.26, 55.31, 61.32]
				],
				[
					[-2.34, -2.34, -2.34, -1.98, 1.27, 6.57, 12.28, 18.14, 24.07, 30.04, 36.03, 42.03, 48.03, 54.02, 60.02],
					[-2.26, -2.26, -2.22, -1.09, 2.3, 7.48, 13.62, 19.84, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[-2.22, -1.81, -0.38, 1.97, 5.11, 9.4, 14.22, 19.48, 25.13, 31.01, 37.08, 43.14, 49.26, 55.31, 61.32]
				],
				[
					[-3.48, -3.41, -3.19, -2.33, 1.2, 6.54, 12.26, 18.13, 24.06, 30.04, 36.03, 42.03, 48.03, 54.02, 60.02],
					[-3.46, -3.34, -2.92, -1.41, 2.17, 7.43, 13.61, 19.83, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[-3.22, -2.51, -0.88, 1.62, 4.9, 9.27, 14.14, 19.45, 25.11, 31, 37.07, 43.14, 49.26, 55.31, 61.32]
				],
				[
					[-4.66, -4.44, -3.94, -2.65, 1.13, 6.52, 12.25, 18.12, 24.06, 30.03, 36.03, 42.03, 48.03, 54.02, 60.02],
					[-4.59, -4.28, -3.56, -1.72, 2.05, 7.39, 13.6, 19.83, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[-4.11, -3.16, -1.36, 1.27, 4.68, 9.14, 14.06, 19.41, 25.1, 30.99, 37.07, 43.14, 49.26, 55.31, 61.32]
				],
				[
					[-5.69, -5.34, -4.61, -2.95, 1.06, 6.49, 12.24, 18.12, 24.06, 30.03, 36.03, 42.03, 48.03, 54.02, 60.02],
					[-5.58, -5.12, -4.14, -2.01, 1.93, 7.35, 13.59, 19.83, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[-4.91, -3.76, -1.8, 0.95, 4.48, 9, 13.97, 19.38, 25.09, 30.99, 37.07, 43.14, 49.26, 55.31, 61.32]
				]
			],
			[	// tube
				[
					[0.02, 0.07, 0.27, 0.98, 3.09, 7.2, 12.53, 18.25, 24.12, 30.06, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0.03, 0.13, 0.49, 1.56, 3.97, 8.17, 13.84, 19.87, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0.24, 0.78, 2.02, 4.05, 6.84, 10.54, 15.09, 20.08, 25.38, 31.1, 37.12, 43.16, 49.27, 55.31, 61.33]
				],
				[
					[0.02, 0.07, 0.27, 0.98, 3.09, 7.2, 12.53, 18.25, 24.12, 30.06, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0.03, 0.13, 0.49, 1.56, 3.97, 8.17, 13.84, 19.87, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0.24, 0.78, 2.02, 4.05, 6.84, 10.54, 15.09, 20.08, 25.38, 31.1, 37.12, 43.16, 49.27, 55.31, 61.33]
				],
				[
					[0.02, 0.07, 0.27, 0.98, 3.09, 7.2, 12.53, 18.25, 24.12, 30.06, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0.03, 0.13, 0.49, 1.56, 3.97, 8.17, 13.84, 19.87, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0.24, 0.78, 2.02, 4.05, 6.84, 10.54, 15.09, 20.08, 25.38, 31.1, 37.12, 43.16, 49.27, 55.31, 61.33]
				],
				[
					[0.02, 0.07, 0.27, 0.98, 3.09, 7.2, 12.53, 18.25, 24.12, 30.06, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0.03, 0.13, 0.49, 1.56, 3.97, 8.17, 13.84, 19.87, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0.24, 0.78, 2.02, 4.05, 6.84, 10.54, 15.09, 20.08, 25.38, 31.1, 37.12, 43.16, 49.27, 55.31, 61.33]
				],
				[
					[0.02, 0.07, 0.27, 0.98, 3.09, 7.2, 12.53, 18.25, 24.12, 30.06, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0.03, 0.13, 0.49, 1.56, 3.97, 8.17, 13.84, 19.87, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0.24, 0.78, 2.02, 4.05, 6.84, 10.54, 15.09, 20.08, 25.38, 31.1, 37.12, 43.16, 49.27, 55.31, 61.33]
				]
			],
			[	// sine
				[
					[0.01, 0.03, 0.14, 0.55, 2.23, 6.87, 12.41, 18.2, 24.1, 30.05, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0.02, 0.07, 0.27, 1.02, 3.4, 7.89, 13.74, 19.85, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0.13, 0.49, 1.58, 3.59, 6.41, 10.18, 14.84, 19.85, 25.26, 31.06, 37.1, 43.15, 49.27, 55.31, 61.32]
				],
				[
					[0.01, 0.03, 0.14, 0.55, 2.23, 6.87, 12.41, 18.2, 24.1, 30.05, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0.02, 0.07, 0.27, 1.02, 3.4, 7.89, 13.74, 19.85, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0.13, 0.49, 1.58, 3.59, 6.41, 10.18, 14.84, 19.85, 25.26, 31.06, 37.1, 43.15, 49.27, 55.31, 61.32]
				],
				[
					[0.01, 0.03, 0.14, 0.55, 2.23, 6.87, 12.41, 18.2, 24.1, 30.05, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0.02, 0.07, 0.27, 1.02, 3.4, 7.89, 13.74, 19.85, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0.13, 0.49, 1.58, 3.59, 6.41, 10.18, 14.84, 19.85, 25.26, 31.06, 37.1, 43.15, 49.27, 55.31, 61.32]
				],
				[
					[0.01, 0.03, 0.14, 0.55, 2.23, 6.87, 12.41, 18.2, 24.1, 30.05, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0.02, 0.07, 0.27, 1.02, 3.4, 7.89, 13.74, 19.85, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0.13, 0.49, 1.58, 3.59, 6.41, 10.18, 14.84, 19.85, 25.26, 31.06, 37.1, 43.15, 49.27, 55.31, 61.32]
				],
				[
					[0.01, 0.03, 0.14, 0.55, 2.23, 6.87, 12.41, 18.2, 24.1, 30.05, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0.02, 0.07, 0.27, 1.02, 3.4, 7.89, 13.74, 19.85, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0.13, 0.49, 1.58, 3.59, 6.41, 10.18, 14.84, 19.85, 25.26, 31.06, 37.1, 43.15, 49.27, 55.31, 61.32]
				]
			],
			[	// diode
				[
					[0.33, 0.65, 1.27, 2.45, 4.58, 8.05, 12.85, 18.39, 24.19, 30.09, 36.05, 42.03, 48.03, 54.02, 60.02],
					[0.42, 0.83, 1.6, 2.97, 5.25, 8.86, 14.1, 19.92, 25.63, 31.47, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0.99, 1.84, 3.22, 5.25, 7.95, 11.39, 15.7, 20.57, 25.69, 31.2, 37.16, 43.18, 49.28, 55.32, 61.33]
				],
				[
					[0.36, 0.71, 1.4, 2.68, 4.98, 8.64, 13.57, 19.15, 24.97, 30.88, 36.84, 42.82, 48.81, 54.81, 60.81],
					[0.46, 0.9, 1.74, 3.22, 5.62, 9.27, 14.34, 19.98, 25.61, 31.45, 37.42, 43.42, 49.41, 55.41, 61.4],
					[1.06, 1.96, 3.42, 5.55, 8.33, 11.82, 16.16, 21.04, 26.19, 31.65, 37.56, 43.57, 49.65, 55.69, 61.7]
				],
				[
					[0.41, 0.81, 1.58, 3.02, 5.51, 9.35, 14.38, 20, 25.83, 31.75, 37.71, 43.69, 49.68, 55.68, 61.68],
					[0.52, 1.02, 1.94, 3.55, 6.1, 9.8, 14.69, 20.03, 25.56, 31.43, 37.41, 43.4, 49.4, 55.4, 61.4],
					[1.15, 2.12, 3.68, 5.91, 8.78, 12.32, 16.69, 21.58, 26.79, 32.25, 38.12, 44.08, 50.11, 56.12, 62.12]
				],
				[
					[0.5, 0.98, 1.89, 3.52, 6.23, 10.22, 15.3, 20.94, 26.79, 32.71, 38.68, 44.66, 50.66, 56.65, 62.64],
					[0.62, 1.2, 2.25, 4.02, 6.73, 10.45, 15.13, 20.09, 25.52, 31.41, 37.38, 43.38, 49.38, 55.38, 61.38],
					[1.3, 2.37, 4.04, 6.36, 9.32, 12.91, 17.29, 22.22, 27.51, 33.06, 38.84, 44.74, 50.68, 56.66, 62.66]
				],
				[
					[0.69, 1.32, 2.46, 4.37, 7.26, 11.29, 16.37, 22.02, 27.88, 33.81, 39.77, 45.76, 51.75, 57.75, 63.74],
					[0.82, 1.55, 2.79, 4.75, 7.57, 11.21, 15.61, 20.18, 25.51, 31.38, 37.36, 43.35, 49.35, 55.36, 61.36],
					[1.58, 2.77, 4.55, 6.96, 9.98, 13.61, 18.01, 22.97, 28.39, 34.05, 39.75, 45.58, 51.43, 57.37, 63.34]
				]
			],
			[	// fold
				[
					[-1.52, -1.52, -1.52, -0.29, 9.36, 13.91, 18, 21.22, 25.11, 30.53, 36.28, 42.15, 48.08, 54.05, 60.04],
					[-1.52, -1.52, -1.28, 1.7, 7.16, 13.03, 17.08, 20.51, 25.69, 31.56, 37.57, 43.59, 49.6, 55.61, 61.61],
					[-1.47, -0.29, 1.98, 5.02, 9.17, 14.27, 18.85, 24.1, 28.74, 33.16, 38.28, 43.74, 49.68, 55.64, 61.62]
				],
				[
					[-11.76, -11.76, -3.45, 2.1, 8.25, 12.87, 13.44, 18.64, 24.34, 30.18, 36.1, 42.06, 48.03, 54.02, 60.02],
					[-11.72, -9.82, -4.52, 1.9, 9.51, 12.14, 15.21, 20.17, 25.7, 31.6, 37.59, 43.6, 49.6, 55.6, 61.6],
					[-9.03, -6.11, -2.49, 2.92, 7.35, 12.5, 17.24, 21.72, 26.55, 31.58, 37.38, 43.36, 49.45, 55.49, 61.49]
				],
				[
					[-16.33, -10.74, -5.52, 2.2, 7.81, 7.81, 12.76, 18.4, 24.21, 30.11, 36.07, 42.04, 48.03, 54.02, 60.02],
					[-15.21, -10.51, -4.3, 3.61, 7.8, 10.13, 14.65, 20.08, 25.69, 31.6, 37.59, 43.6, 49.6, 55.6, 61.6],
					[-11.4, -8.09, -2.95, 1.74, 6.81, 11.63, 15.93, 20.87, 25.76, 31.29, 37.27, 43.31, 49.43, 55.47, 61.48]
				],
				[
					[-18.33, -8.61, -3.68, 0.3, 3.42, 7.15, 12.54, 18.29, 24.15, 30.09, 36.05, 42.03, 48.03, 54.02, 60.02],
					[-16.25, -10.5, -3.5, 3.76, 5.8, 9.24, 14.38, 20.04, 25.69, 31.6, 37.59, 43.6, 49.6, 55.6, 61.6],
					[-12.86, -8.75, -3.46, 1.14, 6.37, 10.74, 15.41, 20.31, 25.43, 31.19, 37.22, 43.29, 49.42, 55.47, 61.48]
				],
				[
					[-17.37, -11.25, -3.98, 1.44, 2.09, 6.85, 12.44, 18.22, 24.12, 30.07, 36.04, 42.03, 48.03, 54.02, 60.02],
					[-16.49, -10.4, -2.6, 2.64, 4.61, 8.77, 14.24, 20.02, 25.69, 31.6, 37.59, 43.6, 49.59, 55.6, 61.6],
					[-13.8, -8.92, -4.07, 0.94, 5.92, 10.05, 14.95, 19.9, 25.28, 31.14, 37.2, 43.28, 49.41, 55.46, 61.48]
				]
			],
			[	// wrap
				[
					[0, 0, 0, 0, 3.53, 10.81, 16.84, 22.49, 32.97, 47.24, 63.62, 72.35, 79.73, 87.56, 89.49],
					[0, 0, 0, 0.92, 5.62, 12.63, 17.24, 20.82, 25.8, 31.44, 37.33, 43.29, 49.31, 55.32, 61.32],
					[0, 0.26, 1.9, 4.68, 7.93, 12.77, 18.04, 23.78, 30.72, 37.62, 44.36, 51.52, 58.35, 64.53, 70.7]
				],
				[
					[0, 0, 0, 0, 6.09, 14.55, 19.12, 24.27, 34.85, 47.95, 62.17, 73.05, 79.57, 88.91, 90.73],
					[0, 0, 0, 1.27, 6.76, 13.62, 17.4, 20.84, 25.8, 31.44, 37.33, 43.28, 49.31, 55.32, 61.32],
					[0, 0.39, 2.29, 5.28, 8.67, 14.17, 19.27, 25.37, 32.49, 39.14, 45.63, 52.38, 58.7, 64.68, 70.74]
				],
				[
					[0, 0, 0, 0, 7.46, 17.16, 20.2, 24.98, 35.24, 45.86, 59.07, 72.49, 78.4, 87.43, 91.4],
					[0, 0, 0, 1.36, 6.96, 13.3, 17.26, 20.82, 25.8, 31.44, 37.33, 43.28, 49.3, 55.32, 61.32],
					[0, 0.43, 2.4, 5.42, 8.86, 14.64, 19.66, 25.95, 33.13, 39.69, 46.1, 52.67, 58.77, 64.68, 70.72]
				],
				[
					[0, 0, 0, 0, 6.04, 14.44, 18.91, 23.96, 33.76, 43.14, 56.33, 71.04, 76.8, 84.8, 91.19],
					[0, 0, 0, 1.13, 6.05, 11.89, 16.84, 20.76, 25.79, 31.44, 37.33, 43.28, 49.31, 55.32, 61.32],
					[0, 0.36, 2.2, 5.05, 8.39, 13.79, 18.9, 24.97, 32.01, 38.74, 45.28, 52.14, 58.5, 64.55, 70.64]
				],
				[
					[0, 0, 0, 0, 3.43, 10.69, 16.54, 22.03, 31.6, 40.77, 54.11, 69.34, 75.18, 82.36, 90.21],
					[0, 0, 0, 0.63, 4.58, 10.31, 16.25, 20.69, 25.78, 31.44, 37.33, 43.29, 49.31, 55.32, 61.32],
					[0, 0.2, 1.73, 4.27, 7.44, 12.25, 17.49, 23.26, 30.12, 37.09, 43.88, 51.16, 58.04, 64.33, 70.51]
				]
			],
			[	// gap
				[
					[0, 0, 0, 0, 1.66, 6.72, 12.34, 18.17, 24.08, 30.04, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0, 0, 0, 0.37, 2.94, 7.71, 13.68, 19.84, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0, 0.11, 1.13, 3.17, 6, 9.91, 14.62, 19.67, 25.2, 31.04, 37.09, 43.15, 49.27, 55.31, 61.32]
				],
				[
					[0.98, 0.98, 0.98, 0.98, 1.8, 6.77, 12.36, 18.18, 24.09, 30.05, 36.03, 42.03, 48.03, 54.02, 60.02],
					[1.18, 1.18, 1.18, 1.18, 3.21, 7.81, 13.71, 19.85, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[2.55, 2.55, 2.55, 4.02, 6.47, 10.16, 14.77, 19.73, 25.22, 31.05, 37.1, 43.15, 49.27, 55.31, 61.32]
				],
				[
					[1.94, 1.94, 1.94, 1.94, 1.94, 6.82, 12.39, 18.19, 24.1, 30.05, 36.03, 42.03, 48.03, 54.02, 60.02],
					[2.03, 2.03, 2.03, 2.03, 3.5, 7.93, 13.74, 19.85, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[3.53, 3.53, 3.53, 4.73, 6.89, 10.39, 14.93, 19.8, 25.25, 31.06, 37.1, 43.15, 49.27, 55.31, 61.32]
				],
				[
					[2.09, 2.09, 2.09, 2.09, 2.09, 6.87, 12.41, 18.2, 24.1, 30.05, 36.03, 42.03, 48.03, 54.02, 60.02],
					[2.93, 2.93, 2.93, 2.93, 3.8, 8.06, 13.78, 19.85, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[4.3, 4.3, 4.3, 5.31, 7.3, 10.59, 15.09, 19.87, 25.27, 31.07, 37.11, 43.16, 49.27, 55.31, 61.32]
				],
				[
					[2.24, 2.24, 2.24, 2.24, 2.24, 6.92, 12.43, 18.21, 24.11, 30.06, 36.03, 42.03, 48.03, 54.02, 60.02],
					[3.86, 3.86, 3.86, 3.86, 4.11, 8.19, 13.82, 19.86, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[4.98, 4.98, 4.98, 5.79, 7.7, 10.76, 15.25, 19.94, 25.3, 31.08, 37.11, 43.16, 49.27, 55.31, 61.32]
				]
			],
			[	// rectify
				[
					[0, 0, 0, 0, 1.66, 6.72, 12.34, 18.17, 24.08, 30.04, 36.03, 42.03, 48.03, 54.02, 60.02],
					[0, 0, 0, 0.37, 2.94, 7.71, 13.68, 19.84, 25.63, 31.46, 37.42, 43.42, 49.4, 55.4, 61.4],
					[0, 0.11, 1.13, 3.17, 6, 9.91, 14.62, 19.67, 25.2, 31.04, 37.09, 43.15, 49.27, 55.31, 61.32]
				],
				[
					[2.41, 2.41, 2.41, 2.41, 4.09, 9.18, 14.82, 20.66, 26.58, 32.54, 38.52, 44.51, 50.51, 56.51, 62.5],
					[2.15, 2.15, 2.15, 2.57, 5.08, 9.46, 14.78, 20.09, 25.55, 31.42, 37.39, 43.39, 49.38, 55.38, 61.38],
					[1.64, 1.8, 3.04, 5.16, 7.98, 11.77, 16.43, 21.48, 27.05, 32.81, 38.68, 44.62, 50.58, 56.58, 62.57]
				],
				[
					[5.27, 5.27, 5.27, 5.27, 7.1, 12.43, 18.2, 24.11, 30.06, 36.04, 42.03, 48.03, 54.03, 60.03, 66.02],
					[4.21, 4.22, 4.22, 4.76, 7.59, 11.84, 16.23, 20.38, 25.54, 31.37, 37.33, 43.31, 49.31, 55.31, 61.31],
					[2.62, 2.82, 4.28, 6.63, 9.7, 13.73, 18.82, 24.23, 30.1, 36, 41.69, 47.45, 53.17, 59.04, 65]
				],
				[
					[7.72, 7.72, 7.72, 7.72, 10.09, 16.54, 23.12, 29.52, 35.76, 41.91, 48, 54.04, 60.05, 66.05, 72.04],
					[5.13, 5.14, 5.14, 5.8, 9.29, 14.06, 17.29, 20.71, 25.72, 31.4, 37.3, 43.25, 49.27, 55.28, 61.28],
					[2.33, 2.51, 3.97, 6.51, 9.93, 14.48, 20.59, 27.23, 34.06, 40.43, 45.94, 51.5, 56.9, 62.64, 68.56]
				],
				[
					[7.22, 7.22, 7.22, 7.22, 10.07, 18.02, 26.6, 35.38, 44.21, 53.49, 64.14, 75.61, 87.4, 99.42, 107.73],
					[4.05, 4.06, 4.06, 4.7, 8.49, 14.03, 17.38, 20.87, 25.82, 31.44, 37.32, 43.28, 49.29, 55.31, 61.3],
					[0.97, 1.11, 2.35, 4.91, 8.44, 13.22, 19.96, 27.76, 35.99, 43.62, 49.15, 54.49, 59.46, 65.04, 70.92]
				]
			]
		]
	}

	*params {
		^[
			this.knob(\drive, 0, 36, \lin, 6, "dB", "drive",
				"how far past the clip level the signal is pushed - or drag the curve", lag: 0.08, section: \input, dec: 1, size: \big),
			this.knob(\clip, -36, 0, \lin, -6, "dB", "clip at",
				"the level the curve flattens at: nothing passes it (with safe on)", lag: 0.08, section: \input, dec: 1, bipolar: false),
			this.menu(\mode, this.modes, \smooth, "mode",
				"the curve: clean (smooth crisp inflate), colour (tube sine diode), creative (fold wrap gap rectify)", \curve),
			this.knob(\bend, 0, 1, \lin, 0.3, "", "bend",
				"the curve's own character: smooth knee, crisp's start, inflate's curve, diode's lean, fold's depth, wrap to fold, gap's width, half to full rectify",
				box: \pct, lag: 0, section: \curve, size: \big),
			this.knob(\bias, -1, 1, \lin, 0, "", "bias",
				"shifts the signal along the curve: asymmetry, even harmonics", box: \pct, lag: 0, section: \curve),
			this.knob(\heat, -1, 1, \lin, 0, "", "heat",
				"+: loud passages are driven harder and their clipped peaks squashed; -: passages well under the clip level are driven harder and loud steady ones clip less (needs safe to keep the ceiling)", box: \pct, section: \character),
			this.knob(\slew, 0, 1, \lin, 0, "", "slew",
				"dulls the loudest fast edges, as a tired op-amp would", box: \pct, section: \character, size: \small, floorText: "off"),
			this.knob(\detail, 0, 1, \lin, 0, "", "detail",
				"gives back the highs of what was cut: the lows stay clipped", box: \pct, section: \character, floorText: "off"),
			this.knob(\tone, 1000, 20000, \exp, 20000, "Hz", "tone",
				"a low pass over the clipped sound - 20 kHz is off", section: \tone, dec: 0),
			this.knob(\thump, -12, 12, \lin, 0, "dB", "thump",
				"+: the lows hit the curve harder and come out thinner; -: they are spared the colour, and safe clips them if they pass the clip level", section: \lows, dec: 1),
			this.knob(\thumpFreq, 40, 800, \exp, 120, "Hz", "low freq",
				"where thump's shelf turns", section: \lows, dec: 0, size: \small),
			this.menu(\stereo, #[\lr, \ms], \lr, "stereo",
				"lr: each channel clipped alone; ms: the middle and the sides", \stereo),
			this.knob(\skew, 0, 1, \lin, 0, "", "skew",
				"a different bias on each side (or on middle and sides): width from distortion", box: \pct, lag: 0, section: \stereo, size: \small),
			this.toggle(\match, true, "match", "the output kept at the input's loudness: the drive taken back and what the curve takes away given back, never louder than what comes in", \output),
			this.toggle(\safe, true, "safe", "a wall at the clip level after everything, so nothing passes it", \output),
			this.toggle(\delta, false, "delta", "hear only what the clipper takes away (at mix 100)", \output),
			this.mixParams(\output, 1)
		]
	}

	*tidalParams { ^#[\drive, \clip, \mode, \bend, \bias, \heat, \slew, \detail, \tone, \thump, \thumpFreq, \skew, \mix] }

	*devicePages {
		^[
			\clip -> [\input, \curve, (title: "character", keys: [\heat, \detail, \slew])],
			\colour -> [\lows, (title: "tone", keys: [\tone]), \stereo]
		]
	}

	*isActive { |key, unit|
		^switch (key,
			\bend, { #[\tube, \sine].includes(unit.get(\mode)).not },
			\thumpFreq, { unit.get(\thump) != 0 },
			{ true }
		)
	}

	*presetList {
		^[
			\default, (),
			'standard clip', (mode: \smooth, bend: 0.05, drive: 4, clip: -9, detail: 0.3),
			'crisp converter', (mode: \crisp, bend: 0.35, drive: 8, clip: -9),
			'inflator', (mode: \inflate, bend: 0.5, drive: 2, clip: -6),
			'tube bus', (mode: \tube, drive: 9, bias: 0.25, tone: 16000, output: 1.6),		// output: match does not follow bias (measured)
			'density', (mode: \sine, drive: 7),
			'germanium fuzz', (mode: \diode, bend: 0.6, drive: 24, tone: 6000, heat: 0.3),
			'west coast fold', (mode: \fold, bend: 0.45, drive: 10, tone: 4500),
			'digital over', (mode: \wrap, bend: 0, drive: 9, mix: 0.5),
			'sputter', (mode: \gap, bend: 0.5, drive: 6, heat: -0.4),
			'octave peaks', (mode: \rectify, bend: 1, drive: 12, mix: 0.6),
			'shatter sub', (mode: \tube, bias: 0.3, drive: 3, thump: 12, thumpFreq: 120, output: 4.6)		// the same, and thump is not matched either
		]
	}

	// ------------------------------------------------------------ the model

	// the curve as the settings make it, on Numbers (u and the answer in clip
	// units): what the display draws. side is +1, -1 or 0 (skew's sign)
	*curve { |u, mode, bend, bias = 0, skew = 0, side = 0|
		var b = 0.9 * (bias + (side * skew)).clip(-1, 1);
		^PeakKit.fB(mode, u, bend, b)
	}

	// --------------------------------------------------------------- the DSP

	// everything at control rate, before any audio UGen (the wire buffers stay
	// few only when the kr maths is built first, PeakKit's notes)
	*prKr { |p, sides|
		var modeRaw = p[\mode], betaRaw = p[\bend], biasRaw = p[\bias], skewRaw = p[\skew];
		var famRaw = Select.kr(modeRaw, PeakKit.familyOf);
		var isPoly = Select.kr(modeRaw, PeakKit.isPoly);
		var polyRaw = Gate.kr(Select.kr(modeRaw, PeakKit.polyIndexOf), isPoly);
		var targets = { |fam| 5.collect { |j| (fam - j).abs < 0.5 } };
		var perMode = { |beta, idx|
			var all = PeakKit.polyMembers.collect { |m| PeakKit.polyCoefs(m, beta) };
			11.collect { |i| Select.kr(idx, all.collect(_[i])) }
		};
		// 8 ms late, so the curve starts moving only once mv has reached 1: mv
		// takes 4 blocks (5.3 ms at 48 kHz), and a kr DelayN of 5 ms is 3 blocks,
		// which let the divided difference see one block of a moving curve (a
		// zipper, measured). For the first 8 ms the values themselves (DelayN
		// starts from zeros, which morphed every new synth from smooth)
		var raw = [betaRaw, biasRaw, skewRaw, polyRaw, famRaw, modeRaw];
		var late = Select.kr(Sweep.kr > 0.0085, [raw, DelayN.kr(raw, 0.008, 0.008)]);
		var betaL = Lag.kr(late[0], 0.02), biasL = Lag.kr(late[1], 0.02), skewL = Lag.kr(late[2], 0.02);
		var coefL = Lag.kr(perMode.(betaL, late[3]), 0.02);
		var w = Lag.kr(targets.(late[4]), 0.02);
		var moving = ((betaRaw - betaL).abs + (biasRaw - biasL).abs + (skewRaw - skewL).abs
			+ (targets.(famRaw) - w).abs.sum
			+ (perMode.(betaRaw, polyRaw) - coefL).abs.sum) > 1e-4;
		var mv = Slew.kr(moving, 200, 200);
		var pp = PeakKit.polyPrep(coefL), ll = PeakKit.diodeL(betaL), aa = PeakKit.foldA(betaL);
		// match's loss table at the curve's own mode and bend (late, as the curve): per crest, per level
		var table = this.matchTable, nb = table[0].size, nc = this.matchCrests.size;
		var lossVec = Lag.kr(nc.collect { |c| this.matchLevels.size.collect { |j|
			LinSelectX.kr(betaL * (nb - 1), nb.collect { |b| Select.kr(late[5], table.collect { |row| row[b][c][j] }) })
		} }, 0.02);
		var k = (
			w: w, pp: pp, ll: ll, aa: aa, wb: betaL, mv: mv, eps: eps,
			moving: moving, betaL: betaL, biasL: biasL, skewL: skewL, coefL: coefL,
			gIn: (p[\drive] - p[\clip]).dbamp,
			clipAmp: p[\clip].dbamp,
			lossVec: lossVec,
			matchOn: Lag.kr(p[\match], 0.05),
			slewRate: 2pi * 20000 * (0.075 ** p[\slew]),
			slewOn: Lag.kr(p[\slew] > 0, 0.05),
			toneOn: Lag.kr(p[\tone] < 19900, 0.05),
			safeOn: Lag.kr(p[\safe], 0.02),
			deltaOn: Lag.kr(p[\delta], 0.02),
			dcCoef: (-2pi * 3 * SampleDur.ir).exp,
			thumpOn: Lag.kr(p[\thump].abs > 0.001, 0.05)
		);
		// the offset along the curve, per side, and its correction (kr)
		k[\sides] = IdentityDictionary.new;
		sides.do { |side|
			var b = 0.9 * (biasL + (side * skewL)).clip(-1, 1);
			var fb = PeakKit.polyf(b, pp) * w[0];
			fb = fb + (PeakKit.tubef(b) * w[1]) + (PeakKit.diodef(b, ll) * w[2])
				+ (PeakKit.foldf(b, aa) * w[3]) + (PeakKit.wrapf(b, betaL) * w[4]);
			k[\sides][side] = (b: b, fb: fb, nInv: (1 + fb.abs).reciprocal);
		};
		^k
	}

	// The audio input waits for every kr value. scsynth has 64 audio wire
	// buffers by default, and how many a def needs depends on the order
	// sclang's topological sort gives its UGens. The sort walks depth first
	// from whatever is ready, and the input (In.ar, made before this method
	// runs) is ready first: the audio chain then starts before its kr inputs
	// exist, and both channels' four evaluations run side by side, holding
	// their partial sums. Measured: 62 live wires mono, 111 stereo, so a
	// stereo def did not load. Adding a kr zero that depends on all of the kr
	// maths to the input holds the audio back until the kr is done
	*prAfterKr { |sig, p, k|
		var all = List.new, zero;
		var collect = { |v|
			case
			{ v.isKindOf(UGen) } { if (v.rate == \control) { all.add(v) } }
			{ v.isKindOf(Dictionary) } { v.do(collect) }
			{ v.isArray } { v.do(collect) }
		};
		collect.(k); collect.(p);
		zero = all.asArray.sum * DC.kr(0);
		^sig.collect { |x| x + zero }
	}

	// one channel up to the end of its own path: [y, rOut, u, lin, yu0, hg].
	// y and rOut are before safe and makeup, which act after the ms decode
	*prChannel { |x, side, p, k|
		var s = k[\sides][side];
		var e, u, env, hg, xp, yk, yu, yu0, lin, r, g, y, rOut;
		// the shelves only while thump is not 0: a 0 dB biquad is not quite a wire in float32
		e = x + ((BLowShelf.ar(x, p[\thumpFreq], 1, p[\thump]) - x) * k[\thumpOn]);
		u = e * k[\gIn];
		env = Amplitude.ar(u, 0.01, 0.2);
		hg = (12 * p[\heat] * (env.clip(0, 1) - 0.5)).dbamp;
		u = (u * hg).clip2(16);
		xp = PeakKit.pre(u);
		yk = PeakKit.bank(xp, k.copy.putAll(s));
		yu0 = yu = PeakKit.post(yk);
		lin = PeakKit.twinOfPre(xp);
		r = lin - yu;
		yu = yu + (p[\detail] * (r - LPF.ar(LPF.ar(r, 3000), 3000)));
		// DC off the removed part only: its slowest part (three one-poles at 3 Hz)
		// given back. A 10 Hz LeakDC on the residual let a driven 40 Hz sine out
		// 13 dB over the clip level (the residual is as big as the drive)
		// The filters hold the residual in input units (divided by the drive's
		// gain, multiplied back after): a drive step down then scales what they
		// hold at once. Held in clip units, a step down from drive 24 let pink's
		// sub-3 Hz part of the old, huge residual out for 100 ms (+11 dB, to the
		// clip level: measured, t2c #3)
		r = (lin - yu) / k[\gIn];
		yu = yu + (OnePole.ar(OnePole.ar(OnePole.ar(r, k[\dcCoef]), k[\dcCoef]), k[\dcCoef]) * k[\gIn]);
		yu = yu + ((Slew.ar(yu, k[\slewRate], k[\slewRate]) - yu) * k[\slewOn]);
		g = k[\clipAmp] / Delay1.ar(hg);
		y = yu * g;
		y = y + ((BLowShelf.ar(y, p[\thumpFreq], 1, p[\thump].neg) - y) * k[\thumpOn]);
		y = y + ((LPF.ar(y, p[\tone]) - y) * k[\toneOn]);
		rOut = (lin - yu) * g;
		rOut = rOut + ((BLowShelf.ar(rOut, p[\thumpFreq], 1, p[\thump].neg) - rOut) * k[\thumpOn]);
		^[y, rOut, u, lin, yu0, hg, yu]
	}

	// match's makeup (kr): the drive back at once (the same lagged drive as the
	// input gain, no lag of its own, or a drive step bursts), plus the curve's
	// loss at the level the loudest chain goes over the clip level, at its
	// crest. Never above
	// 0 dB, so safe's ceiling holds with match on, and an input already over
	// the clip level at drive 0 comes out quieter, not louder
	*prMatch { |ins, p, k|
		var lv = this.matchLevels, top = lv.size - 1, step = lv[1] - lv[0], cs = this.matchCrests;
		var t60 = matchTime * 6.91, pw, pk, lvl, crest, ci, vec, loss, g;
		pw = ins.collect { |x| A2K.kr(Lag.ar(x.squared, t60)) }.reduce(\max);
		pk = ins.collect { |x| A2K.kr(LagUD.ar(x.abs, 0, t60)) }.reduce(\max);
		lvl = (pw.max(1e-24).log10 * 10) + p[\drive] - p[\clip];
		crest = (pk.max(1e-12).log10 * 20) - (pw.max(1e-24).log10 * 10);
		ci = Select.kr(crest > cs[1], [(crest - cs[0]) / (cs[1] - cs[0]), 1 + ((crest - cs[1]) / (cs[2] - cs[1]))]).clip(0, cs.size - 1);
		vec = lv.size.collect { |j| LinSelectX.kr(ci, k[\lossVec].collect(_[j])) };
		loss = LinSelectX.kr(((lvl - lv[0]) / step).clip(0, top), vec)
			+ ((lvl - lv.last).max(0) * ((vec[top] - vec[top - 1]) / step));
		g = (loss - p[\drive]).min(0).dbamp;
		^1 + (k[\matchOn] * (g - 1))
	}

	// safe, makeup, delta: on what leaves, after the ms decode
	*prFinish { |y, rOut, k|
		y = y + ((y.clip2(k[\clipAmp]) - y) * k[\safeOn]);
		y = y * k[\mk];
		^y + (((rOut * k[\mk]) - y) * k[\deltaOn])
	}

	*prExtras { |c, k|
		var rate = FxParamUnit.meterRate, trig = Impulse.kr(rate), n = this.dotPairs;
		var cutPow = A2K.kr(Lag.ar((c[3] - c[6]).squared, 0.1));
		var inPow = A2K.kr(Lag.ar(c[3].squared, 0.1));
		var cut = (((cutPow + 1e-20) / (inPow + 1e-20)).log10 * 10).clip(-120, 40);
		var dots = n.collect { |j|
			var t = Impulse.kr(rate, j / n);
			[Latch.kr(c[3], t), Latch.kr(c[4], t)]
		}.flat;
		^[Peak.ar(c[2].abs, trig), cut, A2K.kr(c[5])] ++ dots
	}

	*process { |sig, p, ctx|
		var n = sig.size, sides = [], k, ms = p[\stereo], outs = [], first, chains = [], pairs;
		if (n > 1) { sides = [1, -1] };
		if (n.odd) { sides = sides.add(0) };
		k = this.prKr(p, sides);
		sig = this.prAfterKr(sig, p, k);
		pairs = (n div: 2).collect { |i|
			var l = sig[i * 2], r = sig[i * 2 + 1];
			[Select.ar(ms, [l, (l + r) * 0.5]), Select.ar(ms, [r, (l - r) * 0.5])]
		};
		k[\mk] = this.prMatch(pairs.flat ++ (if (n.odd) { [sig.last] } { [] }), p, k);
		(n div: 2).do { |i|
			var a = pairs[i][0], b = pairs[i][1];
			var ca = this.prChannel(a, 1, p, k), cb = this.prChannel(b, -1, p, k);
			var yl = Select.ar(ms, [ca[0], ca[0] + cb[0]]), yr = Select.ar(ms, [cb[0], ca[0] - cb[0]]);
			var rl = Select.ar(ms, [ca[1], ca[1] + cb[1]]), rr = Select.ar(ms, [cb[1], ca[1] - cb[1]]);
			if (i == 0) { first = ca };
			chains = chains ++ [ca, cb];
			outs = outs ++ [this.prFinish(yl, rl, k), this.prFinish(yr, rr, k)];
		};
		if (n.odd) {
			var c = this.prChannel(sig.last, 0, p, k);
			if (first.isNil) { first = c };
			chains = chains.add(c);
			outs = outs.add(this.prFinish(c[0], c[1], k));
		};
		ctx[\dry] = sig.collect { |x| PeakKit.linearTwin(x) };
		ctx[\extras] = if (buildExtras) { this.prExtras(first, k) };
		probe !? { |pr|
			pr.putAll(k);
			pr[\mk] = k[\mk];
			pr[\first] = first;
			pr[\chains] = chains;	// per chain [y, rOut, u, lin, yu0, hg, yu]
			pr[\dry] = ctx[\dry];
			pr[\extras] = ctx[\extras];
			pr[\outs] = outs;
		};
		^outs
	}
}
