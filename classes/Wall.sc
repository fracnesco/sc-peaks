// =====================================================================
// Wall - a lookahead brickwall limiter: true peak, two-stage release
//
// A limiter turns the sound down just enough that nothing passes a level
// (`limit`, the ceiling). Wall looks a little ahead (`ahead`), so it can
// start turning down before a peak arrives instead of clipping it, and by
// default it watches the peaks between the samples too (`truePeak`), the
// ones a converter or an mp3 makes. `input` pushes the sound into the
// ceiling: louder, until it is squashed.
//
//   Ndef(\mix, { Wall.ar(SinOsc.ar([110, 111]) * LFPulse.kr(2).range(0.2, 1.5), \wall) }).play;
//   w = Wall(Ndef(\mix), \wall);
//   w.gui;
//   w.input = 6; w.limit = -1;
//   w.style = \punchy; w.recovery = 0.04;
//   w.preset = 'loud';
//   w.delta = true;                      // only what the limiter takes away
//
// Looking ahead puts the sound late: (w - 1) + 6 samples, w the window in
// samples (0.5 ms = 24 at 48 kHz, so 29 samples). SuperDirt has no delay
// compensation: on an orbit that orbit comes late against the others, and
// the same sound on two orbits combs. `none` is 6 samples late, but it is
// a clipper with a release, not a limiter.
//
// Styles: clean (two release stages: fast on transients, steady on bass),
// punchy (the fast stage alone), loud (a soft clip first, the limiter takes
// the rest), pump (a release linear in dB: it breathes). Every time is a
// time constant (63 %), except pump's: the time to come back 6 dB.
//
// No `mix`, and `output` only turns down: the ceiling is the unit's last
// word. Nothing after the unit (a strip's fader above 0 dB) is held by it.
// =====================================================================

Wall : FxParamUnit {
	classvar <>eps = 0.02;				// the loud pre-clip's ADAA threshold (Shear's measured value)
	classvar <>buildExtras = true;		// false: the same graph without the readouts (a null test, CPU)
	classvar <>probe;					// an Event: *process leaves its insides there (checks only)

	*controlName { ^\wall }
	*displayClass { ^\WallView.asClass }
	*guiClass { ^FxDeviceGUI }
	*panelClass { ^FxDevicePanel }
	*windowSize { ^Point(900, 560) }
	*insertCategory { ^\dynamics }
	*hasTail { ^false }
	*usesTidalClock { ^false }

	*identityColour { ^FxPalette.identity(\dynamics) ?? { Color(0.92, 0.50, 0.96) } }

	// the meter's gain bar (R-FACE-6): the display's heroGain, wired in while the
	// device panel builds its header (meter and display exist by then)
	*panelExtras { |panel|
		var m = panel.tryPerform(\meter), d = panel.tryPerform(\display);
		if (m.isKindOf(FxMeter) and: { d.respondsTo(\heroGain) }) { m.gain = { d.heroGain } };
		^[]
	}

	*aheads { ^#['none', '0.5 ms', '1.5 ms', '3 ms', '6 ms'] }
	*aheadSeconds { ^#[0, 0.0005, 0.0015, 0.003, 0.006] }
	*styles { ^#[\clean, \punchy, \loud, \pump] }

	// per style: which release (0 two-stage, 1 fast only, 2 linear in dB) and
	// the slow stage's time constants (release, attack), in seconds
	*styleRelease { ^#[0, 1, 0, 2] }
	*slowRelTau { ^#[0.6, 0.6, 0.3, 0.6] }
	*slowAttTau { ^#[0.06, 0.06, 0.03, 0.06] }
	*tau60 { ^1000.log }					// a 60 dB time (Lag, LagUD) is 6.91 time constants

	// gain reduction (dB, the deepest since the last reply), output true peak
	// (dBTP, after `output`), latency (samples), short-term loudness (LUFS)
	*extrasSize { ^4 }

	*params {
		^[
			this.knob(\input, -12, 24, \lin, 0, "dB", "input",
				"how hard the signal is pushed into the ceiling - or drag the history up", section: \level, dec: 1, size: \big, bipolar: false),
			this.knob(\limit, -12, 0, \lin, -1, "dB", "ceiling",
				"nothing goes over this: in true peak (dBTP) when true peak is on", section: \level, dec: 1, size: \big, bipolar: false),
			this.menu(\ahead, this.aheads, '0.5 ms', "ahead",
				"how far ahead it looks: longer is cleaner but puts this track that much late, and combs with the same sound on another orbit. none = a clipper with a release (6 samples late)", \time),
			this.knob(\recovery, 0.005, 2, \exp, 0.1, "s", "release",
				"how quickly the level comes back after a peak (a time constant; in pump, the time to come back 6 dB)", box: \ms, section: \time),
			this.menu(\style, this.styles, \clean, "style",
				"clean: steady on bass; punchy: snappier, pumps when pushed; loud: a clipper first; pump: breathes on purpose", \time),
			this.knob(\link, 0, 1, \lin, 1, "", "link",
				"100: every channel turned down together, the image stays; 0: each alone", box: \pct, section: \detect, size: \small),
			this.toggle(\truePeak, true, "true peak", "catch the peaks between samples, as a converter or an mp3 makes them", \detect),
			this.toggle(\match, false, "match", "the output turned down by the input gain: hear the limiting, not the loudness", \output),
			this.toggle(\delta, false, "delta", "hear only what the limiter takes away", \output),
			this.knob(\output, -24, 0, \lin, 0, "dB", "output",
				"the level after the limiter, only down: the ceiling stays the last word", section: \output, dec: 1)
		]
	}

	*tidalParams { ^#[\input, \limit, \recovery, \link] }

	*devicePages { ^[\wall -> [\level, \time, (title: "detect", keys: [\link, \truePeak])]] }

	*presetList {
		^[
			\default, (),
			'modern', (style: \clean, ahead: '1.5 ms', recovery: 0.06, limit: -1, input: 3),
			'master safe', (style: \clean, ahead: '6 ms', recovery: 0.25, limit: -1),
			'live limiter', (style: \clean, ahead: '3 ms', recovery: 0.3, limit: -0.3, truePeak: false),
			'punchy drums', (style: \punchy, ahead: '0.5 ms', recovery: 0.04, input: 4),
			'loud', (style: \loud, ahead: '1.5 ms', recovery: 0.05, input: 6),
			'bus pump', (style: \pump, ahead: '0.5 ms', recovery: 0.12, input: 6),
			'l2 classic', (style: \clean, ahead: '1.5 ms', recovery: 0.1, limit: -0.1, input: 6, truePeak: false),
			'orbit guard', (style: \clean, ahead: '0.5 ms', recovery: 0.08, limit: -3),
			'what it takes', (style: \clean, input: 9, delta: true)
		]
	}

	// ------------------------------------------------------------ the model

	// the window of an ahead item in samples (1 for none)
	*windowOf { |ahead, sr = 48000|
		var i = this.aheads.indexOf(ahead.asSymbol) ? 1;
		^if (i == 0) { 1 } { (this.aheadSeconds[i] * sr).round.max(2).asInteger }
	}

	// how late the unit is, in samples: (w - 1) + 6, and in loud the pre-clip's
	// allpass (0.71 samples at DC, 1.41 at Nyquist) counted as 1
	*latency { |ahead = '0.5 ms', style = \clean, sr = 48000|
		^(this.windowOf(ahead, sr) - 1) + 6 + if (style.asSymbol == \loud) { 1 } { 0 }
	}

	// the static curve the display draws: output level (dB) for a steady input
	// level (dB, before `input`), at the ceiling `limit`. loud's soft clip
	// bends from 4 dB under the ceiling + 2
	*curve { |inDb, input = 0, limit = -1, style = \clean|
		var x = (inDb + input).dbamp, c = limit.dbamp, c2 = c * 2.dbamp, y;
		y = if (style.asSymbol == \loud) {
			c2 * PeakKit.f(\smooth, x / c2, 0.5)
		} { x };
		^y.min(c).ampdb
	}

	// --------------------------------------------------------------- the DSP

	// everything at control rate (and ir), before any audio UGen
	*prKr { |p|
		var sr = SampleRate.ir, t60 = this.tau60, style = p[\style];
		var ws = this.aheadSeconds.drop(1).collect { |s| (s * sr).round.max(2) };
		var lats = [6] ++ ws.collect { |w| w + 5 };
		var latS = Select.kr(p[\ahead], lats);
		^(
			ws: ws,
			ai: p[\ahead],
			latS: latS,
			latT: (latS + 0.5) * SampleDur.ir,
			loudOn: (style - 2).abs < 0.5,
			relIdx: Select.kr(style, this.styleRelease),
			rec60: p[\recovery] * t60,
			slowRel60: Select.kr(style, this.slowRelTau * t60),
			slowAtt60: Select.kr(style, this.slowAttTau * t60),
			pumpRate: 6 / p[\recovery],
			c: p[\limit].dbamp,
			c2: p[\limit].dbamp * 2.dbamp,
			gIn: p[\input].dbamp,
			deltaOn: Lag.kr(p[\delta], 0.02),
			matchOn: Lag.kr(p[\match], 0.05),
			outAmp: p[\output].dbamp
		)
	}

	// loud's pre-clip: Shear's smooth curve (bend 0.5) at the ceiling + 2 dB,
	// antialiased the transparent way; below the knee it is linearTwin(x)
	*prPreClip { |x, k|
		var kk = PeakKit.polyPrep(PeakKit.polyCoefs(\smooth, 0.5));
		var xp = PeakKit.pre(x / k[\c2]);
		var yk = PeakKit.adaa(xp, { |v| PeakKit.polyF(v, kk) }, { |v| PeakKit.polyf(v, kk) }, eps);
		^PeakKit.post(yk) * k[\c2]
	}

	// the hold: the detector's largest over the window, per ahead item
	*prHold { |d, k| ^Select.ar(k[\ai], [d] ++ PeakKit.windowMax(d, k[\ws])) }

	// the release, before the box (after it would break the proof): every
	// stage falls at once and only rises slowly, so it is never above g
	*prRelease { |g, k|
		var fast = LagUD.ar(g, k[\rec60], 0);
		var slow = LagUD.ar(g, k[\slowRel60], k[\slowAtt60]);
		var pump = Slew.ar(g.ampdb.max(-60), k[\pumpRate], 1e9).dbamp;
		^Select.ar(k[\relIdx], [min(fast, slow), fast, pump])
	}

	// the box: the mean of r over the window (RunningSum of r - 1: near 0,
	// so precise, and transparent from the start). numsamp is read once, so
	// the four lengths run side by side
	*prBox { |r, k|
		^Select.ar(k[\ai], [r] ++ k[\ws].collect { |w| (RunningSum.ar(r - 1, w) / w) + 1 })
	}

	*prExtras { |b, yo, k|
		var trig = Impulse.kr(FxParamUnit.meterRate);
		var gr = Peak.ar(b.reduce(\min).max(1e-9).reciprocal, trig);
		var tp = Peak.ar(yo.collect { |x| PeakKit.truePeak(x) }.reduce(\max), trig);
		^[
			A2K.kr(gr).ampdb.neg,
			A2K.kr(tp).max(1e-9).ampdb,
			k[\latS] + k[\loudOn],
			PeakKit.shortTermLoudness(yo)
		]
	}

	*process { |sig, p, ctx|
		var n = sig.size, k, xg, ps, taps, d, dMax, h, g, r, b, y, twin, dryD, gDry, yo, gD, cEdge, pre;
		k = this.prKr(p);
		sig = PeakKit.afterKr(sig, p, k);
		xg = sig * k[\gIn];
		ps = xg.collect { |x| Select.ar(k[\loudOn], [x, this.prPreClip(x, k)]) };
		// the detector: sample peak (taps 5 and 6) or true peak, the same latency
		d = ps.collect { |x|
			var t = PeakKit.tpTaps(x), ds = PeakKit.samplePeakOf(t);
			Select.ar(p[\truePeak], [ds, PeakKit.tpPoints(t).inject(ds, { |a, v| max(a, v) })])
		};
		// link: never below a channel's own peak
		if (n > 1) {
			dMax = d.reduce(\max);
			d = d.collect { |x| x + (p[\link] * (dMax - x)) };
		};
		h = d.collect { |x| this.prHold(x, k) };
		g = h.collect { |x| (k[\c] / x.max(1e-9)).min(1) };
		r = g.collect { |x| this.prRelease(x, k) };
		b = r.collect { |x| this.prBox(x, k) };
		// what acts on the delayed sound is delayed with it: the input gain (for
		// delta and match) and the ceiling of the last clip2. That clip is at the
		// higher of the ceiling now and the one the gains were worked out for, so
		// a `limit` step from a pattern never reaches it (it is a float guard
		// only); match has no lag of its own on `input` (only on its toggle)
		gD = DelayN.ar(K2A.ar([k[\gIn], k[\c]]), 0.02, k[\latT]);
		cEdge = max(K2A.ar(k[\c]), gD[1]);
		pre = n.collect { |i| DelayN.ar(ps[i], 0.02, k[\latT]) * b[i] };
		y = pre.collect { |x| x.clip2(cEdge) };
		// the dry: the linear path of p without the input gain, lined up
		twin = sig.collect { |x| Select.ar(k[\loudOn], [x, PeakKit.linearTwin(x)]) };
		dryD = twin.collect { |x| DelayN.ar(x, 0.02, k[\latT]) };
		gDry = dryD * gD[0];
		y = n.collect { |i| y[i] + (((gDry[i] - y[i]) - y[i]) * k[\deltaOn]) };
		y = y * (1 + (k[\matchOn] * (gD[0].reciprocal - 1)));
		ctx[\dry] = dryD;
		yo = y * k[\outAmp];
		ctx[\extras] = if (buildExtras) { this.prExtras(b, yo, k) };
		probe !? { |pr|
			pr.putAll(k);
			pr.putAll((ps: ps, d: d, h: h, g: g, r: r, b: b, pre: pre, cEdge: cEdge, gDry: gDry, dryD: dryD, outs: y, yo: yo, extras: ctx[\extras]));
		};
		^y
	}
}
