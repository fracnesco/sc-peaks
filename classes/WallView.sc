// =====================================================================
// WallView - Wall's display: the dynamics face (Pro-L's level view and
// the compressor's ruler)
//
//   ceiling -1.0 dBTP · ahead 0.5 ms (29 samples late) · release 100 ms · clean
//   level                                              curve
//   +------------------------------------------------+ +-------------+
//   | gain reduction hanging from the top, orchid     | |     ________|
//   | - - - - - - - - - - - - - - - - - - -1.0 dBTP   | |    /        |
//   | the output a blue line, the input (with the     | |   /  o      |
//   | input gain) faint blue from below               | |  /          |
//   +------------------------------------------------+ +-------------+
//   -9.8 LUFS · out -1.0 dBTP · gr -3.4 dB
//
// Left, "level", a MEASUREMENT: five seconds of replies scrolling right to
// left on one dB ruler (0 to -36 dB): the input peak plus `input` (what the
// limiter is asked to hold), filled faint from below; the output peak as a
// line; the gain reduction hanging from the top on its own scale (0..-12
// dB, right edge), with its deepest value written at most once a second.
// The ceiling, `limit` + `output`, is the dashed orchid line, in dBTP when
// true peak is on. Under it, the short-term loudness and the output's true
// peak (extras[3], extras[1]).
//
// Right, "curve", a MODEL: the static curve (Wall.curve) from -36 to +12
// dB in, flat at the ceiling, loud's soft knee, the input riding it as a dot.
//
//   drag the dashed ceiling      limit (shift: fine); double click: -1 dB
//   drag anywhere else           input (shift: fine); double click: 0 dB
//   the wheel                    recovery
//   double click outside both    clear the history
//
// heroGain is what the meter's gain bar shows: the deepest reduction of
// the last few replies.
// =====================================================================

WallView : FxParamView {
	classvar <>seconds = 5;
	classvar <>floorDb = -36;
	classvar <>topDb = 3;			// a little room over 0 dB: the input with its gain can pass it

	var ins, outs, grs, size, pos = 0, filled = 0, labels, sinceLabel = 0;
	var drag, dragY, dragFrom, dragShift = false;

	*minHeight { ^220 }

	init { |argUnit, parent|
		super.init(argUnit, parent);
		size = (seconds * FxParamUnit.meterRate).asInteger.max(8);
		this.prClear;
	}

	prClear {
		ins = Array.fill(size, { nil });
		outs = Array.fill(size, { nil });
		grs = Array.fill(size, { nil });
		labels = List.new;
		pos = 0;
		filled = 0;
		sinceLabel = 0;
	}

	clear { this.prClear; this.refresh }

	unit_ { |u| this.prClear; super.unit_(u) }

	isLive { ^unit.notNil and: { unit.meter } and: { unit.isAlive } }

	// ------------------------------------------------------------ the data

	levelsArrived {
		var ex, rate = FxParamUnit.meterRate;
		if (unit.isNil) { ^this };
		ex = unit.extras ? [];
		if (ex.size < Wall.extrasSize) { ^this };
		ins[pos] = (unit.inLevel.max(1e-6).ampdb + unit.get(\input));
		outs[pos] = unit.outLevel.max(1e-6).ampdb;
		grs[pos] = ex[0].min(0);
		// collect/reject answer Arrays: keep labels a List, or add drops a label
		labels = List.newFrom(labels.collect { |l| [l[0] + 1, l[1]] }.reject { |l| l[0] >= size });
		pos = (pos + 1) % size;
		filled = (filled + 1).min(size);
		sinceLabel = sinceLabel + 1;
		// the deepest reduction of the last second, written once a second
		if (sinceLabel >= rate) {
			var n = rate.min(filled), deepest = 0, at = 0;
			n.do { |k| var g = grs[(pos - 1 - k) % size] ? 0; if (g < deepest) { deepest = g; at = k } };
			if (deepest < -0.5) { labels.add([at, deepest]); sinceLabel = 0 };
		};
	}

	heroGain {
		var g = 0;
		if (this.isLive.not) { ^0 };
		4.min(filled).do { |k| g = g.min(grs[(pos - 1 - k) % size] ? 0) };
		^g
	}

	grLabels { ^labels.collect(_[1]) }
	lastGr { ^grs[(pos - 1) % size] }

	ceilingDb { ^unit.get(\limit) + unit.get(\output) }

	sampleRate { ^(unit.tryPerform(\server) ?? { Server.default }).tryPerform(\sampleRate) ? 48000 }

	// ------------------------------------------------------------ geometry

	prSplit { ^(bw * 0.75).round }
	prLevelRect { ^Rect(8, 38, this.prSplit - 16, (bh - 38 - 34).max(40)) }
	prCurveRect {
		var x = this.prSplit + 8, h = (bh - 38 - 34).max(40);
		^Rect(x, 38, (bw - 8 - x).max(40), h)
	}

	prY { |db| var r = this.prLevelRect; ^r.top + (db.linlin(floorDb, topDb, r.height, 0, \minmax)) }
	prYToDb { |y| var r = this.prLevelRect; ^(y - r.top).linlin(r.height, 0, floorDb, topDb, \none) }
	prGrDepth { |db| var r = this.prLevelRect; ^db.neg.linlin(0, 12, 0, r.height * 0.4, \minmax) }

	prCx { |db| var r = this.prCurveRect; ^r.left + (db.linlin(floorDb, 12, 0, r.width, \minmax)) }
	prCy { |db| var r = this.prCurveRect; ^r.top + (db.linlin(floorDb, 12, r.height, 0, \minmax)) }

	prNearCeiling { |x, y|
		^this.prLevelRect.containsPoint(x @ y) and: { (y - this.prY(this.ceilingDb)).abs <= 6 }
	}

	// --------------------------------------------------------------- draw

	prUnitName { ^if (unit.get(\truePeak)) { "dBTP" } { "dBFS" } }

	prCaptionText {
		var ahead = unit.get(\ahead), late = Wall.latency(ahead, unit.get(\style), this.sampleRate);
		var ex = unit.extras ? [];
		if (this.isLive and: { ex.size >= Wall.extrasSize }) { late = ex[2].round.asInteger };
		if (ahead == 'none') { ^"ahead none: a clipper (% samples late) · ceiling % % · %".format(late,
			FxPalette.fixed(this.ceilingDb, 1), this.prUnitName, unit.get(\style)) };
		^"ceiling % % · ahead % (% samples late) · release % · %".format(
			FxPalette.fixed(this.ceilingDb, 1), this.prUnitName, ahead, late,
			FxPalette.format(unit.get(\recovery), "s", \ms), unit.get(\style))
	}

	prDraw {
		var idc = Wall.identityColour;
		this.prCaption(this.prCaptionText, 8, 2, FxPalette.text);
		this.prDrawLevel(idc);
		this.prDrawCurve(idc);
	}

	prDrawLevel { |idc|
		var r = this.prLevelRect, cw = r.width / size, live = this.isLive, cy = this.prY(this.ceilingDb);
		var ex = unit.extras ? [], txt, outPts = [];
		this.prCaption("level", r.left, 20, FxPalette.text);
		Pen.fillColor = FxPalette.bg;
		Pen.addRoundedRect(r, 3, 3);
		Pen.fill;
		// the ruler
		[0, -6, -12, -24, -36].do { |db|
			var y = this.prY(db);
			Pen.fillColor = FxPalette.fade(FxPalette.waveDim, if (db == 0) { 0.8 } { 0.45 });
			Pen.fillRect(Rect(r.left, y.min(r.bottom - 1), r.width, 1));
		};
		// the history, oldest at the left
		if (filled > 0) {
			var xs = filled.collect { |k| r.right - ((filled - k) * cw) };
			var idx = filled.collect { |k| (pos - filled + k) % size };
			Pen.fillColor = FxPalette.fade(FxPalette.wave, 0.18);
			ShearView.fillSteps(xs.collect { |x, k| [x, ins[idx[k]] !? { |v| this.prY(v) }] }, cw, r.bottom);
			xs.do { |x, k| outs[idx[k]] !? { |o| outPts = outPts.add((x + (cw * 0.5)) @ this.prY(o)) } };
		};
		if (outPts.size > 1) {
			Pen.strokeColor = FxPalette.wave;
			Pen.width = 1.5;
			outPts.do { |p, i| if (i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) } };
			Pen.stroke;
		};
		// gain reduction hanging from the top: a flat line at 0 when nothing happens
		if (filled > 0) {
			Pen.fillColor = FxPalette.fade(idc, 0.8);
			ShearView.fillSteps(filled.collect { |k|
				var g = grs[(pos - filled + k) % size] ? 0;
				[r.right - ((filled - k) * cw), if (g < -0.01) { r.top + this.prGrDepth(g) }]
			}, cw, r.top);
		};
		Pen.fillColor = idc;
		Pen.fillRect(Rect(r.left, r.top, r.width, 1));
		// the ceiling
		Pen.strokeColor = idc;
		Pen.width = 1.2;
		ShearView.dash(r.left @ cy, r.right @ cy);
		Pen.stroke;
		// the scales and labels over it all, on chips
		[0, -6, -12, -24, -36].do { |db|
			var y = this.prY(db);
			ShearView.chip(db.asString, Rect(r.left + 2, (y - 13).max(r.top + 2).min(r.bottom - 14), 26, 12), FxPalette.dim);
		};
		[[0, "gr 0"], [-6, "-6"], [-12, "-12 dB"]].do { |a|
			var y = r.top + this.prGrDepth(a[0]);
			Pen.fillColor = FxPalette.fade(idc, 0.6);
			Pen.fillRect(Rect(r.right - 5, y, 5, 1));
			ShearView.chip(a[1], Rect(r.right - 56, y + 2, 48, 12), FxPalette.fade(idc, 0.85), \right);
		};
		labels.do { |l|
			var x = r.right - ((l[0] + 0.5) * cw), y = r.top + this.prGrDepth(l[1]) + 2;
			if (x > (r.left + 30) and: { x < (r.right - 60) }) { ShearView.chip(FxPalette.fixed(l[1], 1), Rect(x - 20, y, 40, 12), idc, \center) };
		};
		ShearView.chip("ceiling % %".format(FxPalette.fixed(this.ceilingDb, 1), this.prUnitName),
			Rect(r.left + 34, cy + 2, 120, 12), idc);
		Pen.stringRightJustIn("now", Rect(r.left, r.bottom + 2, r.width, 12), FxPalette.smallFont, FxPalette.dim);
		Pen.stringLeftJustIn("-% s".format(seconds), Rect(r.left, r.bottom + 2, 40, 12), FxPalette.smallFont, FxPalette.dim);
		// the readouts
		txt = if (live and: { ex.size >= Wall.extrasSize }) {
			"% LUFS short term · out % dBTP · gr % dB".format(
				if (ex[3] < -99) { "-inf" } { FxPalette.fixed(ex[3], 1) },
				if (ex[1] < -99) { "-inf" } { FxPalette.fixed(ex[1], 1) },
				FxPalette.fixed(ex[0].min(0), 1))
		} { "no sound: the history fills while sound passes" };
		Pen.stringLeftJustIn(txt, Rect(r.left, bh - 15, r.width, 13), FxPalette.smallFont, FxPalette.text);
	}

	prDrawCurve { |idc|
		var r = this.prCurveRect, n = r.width.asInteger.max(2), input = unit.get(\input), limit = unit.get(\limit);
		var out = unit.get(\output), style = unit.get(\style), ceil = this.ceilingDb;
		this.prCaption("curve", r.left, 20, FxPalette.text);
		Pen.fillColor = FxPalette.bg;
		Pen.addRoundedRect(r, 3, 3);
		Pen.fill;
		[-36, -24, -12, 0, 12].do { |db|
			Pen.fillColor = FxPalette.fade(FxPalette.waveDim, if (db == 0) { 0.8 } { 0.4 });
			Pen.fillRect(Rect(this.prCx(db), r.top, 1, r.height));
			Pen.fillRect(Rect(r.left, this.prCy(db), r.width, 1));
		};
		[-24, -12, 0, 12].do { |db|
			Pen.stringCenteredIn(db.asString, Rect(this.prCx(db) - 14, r.bottom + 2, 28, 12), FxPalette.smallFont, FxPalette.dim);
		};
		[-24, -12].do { |db|
			Pen.stringLeftJustIn(db.asString, Rect(r.left + 3, this.prCy(db) + 1, 30, 12), FxPalette.smallFont, FxPalette.dim);
		};
		Pen.stringLeftJustIn("out", Rect(r.left + 3, r.top + 2, 30, 12), FxPalette.smallFont, FxPalette.fade(FxPalette.dim, 0.7));
		Pen.stringRightJustIn("in", Rect(r.left, r.bottom - 14, r.width - 4, 12), FxPalette.smallFont, FxPalette.fade(FxPalette.dim, 0.7));
		// y = x, faint
		Pen.strokeColor = FxPalette.fade(FxPalette.dim, 0.35);
		Pen.width = 1;
		Pen.line(this.prCx(floorDb) @ this.prCy(floorDb), this.prCx(12) @ this.prCy(12));
		Pen.stroke;
		// the ceiling
		Pen.strokeColor = idc;
		ShearView.dash(r.left @ this.prCy(ceil), r.right @ this.prCy(ceil));
		Pen.stroke;
		// the curve (model)
		Pen.strokeColor = FxPalette.text;
		Pen.width = 1.5;
		n.do { |px|
			var db = px.linlin(0, n - 1, floorDb, 12);
			var p = (r.left + (px * r.width / (n - 1))) @ this.prCy(Wall.curve(db, input, limit, style) + out);
			if (px == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
		};
		Pen.stroke;
		// the input riding it
		if (this.isLive and: { unit.inLevel > 1e-4 }) {
			var db = unit.inLevel.ampdb;
			var p = this.prCx(db) @ this.prCy(Wall.curve(db, input, limit, style) + out);
			Pen.fillColor = idc;
			Pen.fillOval(Rect(p.x - 4, p.y - 4, 8, 8));
		};
	}

	// ------------------------------------------------------------ gestures

	prMouseDown { |x, y, mod, count|
		var inLevel = this.prLevelRect.containsPoint(x @ y), inCurve = this.prCurveRect.containsPoint(x @ y);
		dragShift = mod.isShift;
		drag = nil;
		if (inLevel.not and: { inCurve.not }) { if (count == 2) { this.clear }; ^this };
		if (this.prNearCeiling(x, y)) {
			if (count == 2) { unit.set(\limit, Wall.param(\limit)[\default]); ^this };
			drag = \limit;
		} {
			if (count == 2) { unit.set(\input, Wall.param(\input)[\default]); ^this };
			drag = \input;
		};
		dragFrom = unit.get(drag);
		dragY = y;
	}

	prMouseMove { |x, y, mod|
		var spec, dbPerPx;
		if (drag.isNil) { ^this };
		if (mod.isShift != dragShift) { dragShift = mod.isShift; dragFrom = unit.get(drag); dragY = y };
		spec = Wall.param(drag)[\spec];
		// the ceiling follows the pointer on the ruler; input takes 200 px for its range
		dbPerPx = if (drag == \limit) { (topDb - floorDb) / this.prLevelRect.height } { (spec.maxval - spec.minval) / 200 };
		unit.set(drag, (dragFrom + ((dragY - y) * dbPerPx * if (dragShift) { 0.1 } { 1 })).clip(spec.minval, spec.maxval));
	}

	prMouseUp { drag = nil }

	prWheel { |x, y, delta|
		var spec = Wall.param(\recovery)[\spec];
		unit.set(\recovery, (unit.get(\recovery) * (1.1 ** delta.sign)).clip(spec.minval, spec.maxval));
	}

	hint { |x, y|
		if (unit.isNil) { ^"" };
		if (x.isNil) { ^"drag the ceiling for the limit, anywhere else for the input, the wheel for the release" };
		^case
		{ this.prNearCeiling(x, y) } {
			"the ceiling, limit + output (now % %): drag it (shift: fine) - double click: -1 dB".format(
				FxPalette.fixed(this.ceilingDb, 1), this.prUnitName)
		}
		{ this.prLevelRect.containsPoint(x @ y) } {
			"measured: the input with its gain (faint), the output (line), the gain reduction from the top - drag up and down for input (now %), the wheel for release (now %)"
			.format(FxPalette.format(unit.get(\input), "dB", nil, 1, true), FxPalette.format(unit.get(\recovery), "s", \ms))
		}
		{ this.prCurveRect.containsPoint(x @ y) } {
			"the curve (the settings): flat at the ceiling, the dot is the input now - drag for input, the wheel for release"
		}
		{ true } { "ceiling · ahead and how late it makes the track · release · style" }
	}
}
