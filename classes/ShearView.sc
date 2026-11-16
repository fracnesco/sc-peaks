// =====================================================================
// ShearView - Shear's display: a distortion curve on a dynamics face
//
//   smooth · bend 30 % · drive +6.0 dB · clip at -6.0 dB · match
//   curve                                 cut
//   smooth crisp inflate · tube sine ...  +-----------------------------+
//   +----------------------------------+  | the input over the clip     |
//   |          ___.------- -6.0 dBFS   |  | level (blue, from below),   |
//   |        /:                        |  | what was cut (orchid, from  |
//   |  -----/-:---------------         |  | the top), five seconds      |
//   |   reach shaded, dots on the curve|  +-----------------------------+
//   +----------------------------------+  3.2 dB over · cut -14 dB · ...
//
// Left, "curve": the transfer curve of the current mode, bend, bias and
// skew (Shear.curve, the same arithmetic the server runs), input along in
// clip-level units (-4..+4, so +-12 dB over), output up. The clip level is
// the dashed orchid line at +-1, labelled in dBFS. The curve is a MODEL of
// the settings. What was measured: the input's reach (inLevel x drive /
// clip, shaded), the 16 dots of each reply (where the signal really sat on
// the curve, fading over half a second) and the big orchid dot, the
// highest |u| since the last reply. With skew, the second side's curve is
// dashed (left/right, or middle/sides in ms).
//
// Right, "cut": a MEASUREMENT, five seconds at the reply rate: how far past
// the clip level the input went (extras[0]) and how loud what the curve
// took away was against the input (extras[1], -40..0 dB, hanging from the
// top on its own scale).
//
//   drag the curve up and down   drive (shift: fine); double click: 6 dB
//   drag a dashed clip line      clip (shift: fine); double click: -6 dB
//   the wheel on the curve       bend; alt + wheel: bias
//   click a mode word            that mode
//   double click the cut history clear it
//
// heroGain is what the meter's gain bar shows: the peak's reduction in dB,
// |f(u)| / |u| at the highest |u| of the last replies, read on the model.
// =====================================================================

ShearView : FxParamView {
	classvar <>seconds = 5;
	classvar <>dotLife = 0.5;

	var overs, cuts, size, pos = 0, filled = 0;
	var dots, wordRects, wheelMod, drag, dragY, dragFrom, dragShift = false, dragSign = 1;
	var recentGain;

	*minHeight { ^220 }

	*wordGroups { ^#[[\smooth, \crisp, \inflate], [\tube, \sine, \diode], [\fold, \wrap, \gap, \rectify]] }

	init { |argUnit, parent|
		super.init(argUnit, parent);
		size = (seconds * FxParamUnit.meterRate).asInteger.max(8);
		this.prClear;
		dots = List.new;
		recentGain = Array.fill(6, { 0 });
		wordRects = [];
		// the base drops a wheel's modifiers: alt-wheel is the bias here
		view.mouseWheelAction = { |v, x, y, mod, xd, yd|
			this.prMeasure;
			wheelMod = mod;
			if (unit.notNil) { this.prWheel(x, y, yd) };
			true
		};
	}

	prClear {
		overs = Array.fill(size, { nil });
		cuts = Array.fill(size, { nil });
		pos = 0;
		filled = 0;
	}

	clear { this.prClear; dots = List.new; this.refresh }

	unit_ { |u| this.prClear; dots = List.new; super.unit_(u) }

	isLive { ^unit.notNil and: { unit.meter } and: { unit.isAlive } }

	// ------------------------------------------------------------ the data

	levelsArrived {
		var ex, now = Main.elapsedTime, n = Shear.dotPairs, u0;
		if (unit.isNil) { ^this };
		ex = unit.extras ? [];
		if (ex.size < Shear.extrasSize) { ^this };
		u0 = ex[0];
		overs[pos] = u0.max(1e-6).ampdb;
		cuts[pos] = ex[1];
		pos = (pos + 1) % size;
		filled = (filled + 1).min(size);
		// a List kept as a List: reject would answer an Array, and Array:add
		// answers a new Array once it is full (the dots stopped at one reply)
		dots.removeAllSuchThat { |d| (now - d[0]) > dotLife };
		n.do { |j| dots.add([now, ex[3 + (j * 2)], ex[4 + (j * 2)]]) };
		recentGain = recentGain.rotate(1).put(0, this.prPeakGain(u0));
	}

	// the peak's reduction (dB, <= 0) at |u| = u0, read on the model
	prPeakGain { |u0|
		var y;
		if (unit.isNil or: { u0 < 1e-3 }) { ^0 };
		y = Shear.curve(u0, unit.get(\mode), unit.get(\bend), unit.get(\bias), unit.get(\skew), 1).abs;
		^(y.max(1e-6) / u0).ampdb.min(0)
	}

	// what the meter's gain bar shows: the deepest of the last replies
	heroGain { ^if (this.isLive) { recentGain.minItem } { 0 } }

	lastOver { ^overs[(pos - 1) % size] }
	lastCut { ^cuts[(pos - 1) % size] }
	lastHeat { ^unit !? { (unit.extras ? [])[2] } }
	dotCount { ^dots.size }

	// ------------------------------------------------------------ geometry

	prSplit { ^((bh - 40) * 1.35).clip(340, bw * 0.62).round }
	prPlot { ^Rect(8, 52, this.prSplit - 16, (bh - 52 - 18).max(40)) }
	prCutRect { var x = this.prSplit + 12; ^Rect(x, 38, (bw - 8 - x).max(40), (bh - 38 - 36).max(40)) }

	prX { |u| var r = this.prPlot; ^r.left + (u.linlin(-4, 4, 0, r.width, \none)) }
	prY { |v| var r = this.prPlot; ^r.top + (v.linlin(-1.25, 1.25, r.height, 0, \none)) }

	prNearClip { |x, y|
		var r = this.prPlot;
		if (r.containsPoint(x @ y).not) { ^nil };
		if ((y - this.prY(1)).abs <= 6) { ^1 };
		if ((y - this.prY(-1)).abs <= 6) { ^-1 };
		^nil
	}

	// where a mode word was drawn last (checks click it)
	wordRect { |mode| wordRects.do { |wr| if (wr[0] == mode.asSymbol) { ^wr[1] } }; ^nil }

	prWordAt { |x, y|
		wordRects.do { |wr| if (wr[1].containsPoint(x @ y)) { ^wr[0] } };
		^nil
	}

	// --------------------------------------------------------------- draw

	*dash { |p1, p2, on = 4, off = 3|
		var len = p1.dist(p2), d = 0, dir;
		if (len < 0.5) { ^this };
		dir = (p2 - p1) / len;
		while { d < len } {
			Pen.line(p1 + (dir * d), p1 + (dir * (d + on).min(len)));
			d = d + on + off;
		};
	}

	// a stepped history as one filled shape (no seams between columns):
	// cols are [x, y] pairs, each column cw wide, filled to baseY
	*fillSteps { |cols, cw, baseY|
		var open = false;
		cols.do { |c|
			if (c[1].isNil) {
				if (open) { Pen.lineTo(c[0] @ baseY); open = false };
			} {
				if (open.not) { Pen.moveTo(c[0] @ baseY); open = true };
				Pen.lineTo(c[0] @ c[1]);
				Pen.lineTo((c[0] + cw) @ c[1]);
			};
		};
		if (open) { Pen.lineTo((cols.last[0] + cw) @ baseY) };
		Pen.fill;
	}

	// a small label on a dark chip, so it reads over a filled history
	*chip { |str, rect, colour, align = \left|
		var font = FxPalette.smallFont, w = str.bounds(font).width.ceil + 6, x;
		x = switch (align, \right, { rect.right - w }, \center, { rect.center.x - (w / 2) }, { rect.left });
		Pen.fillColor = FxPalette.fade(FxPalette.bg, 0.8);
		Pen.addRoundedRect(Rect(x, rect.top, w, rect.height), 2, 2);
		Pen.fill;
		Pen.stringCenteredIn(str, Rect(x, rect.top, w, rect.height), font, colour);
	}

	prCaptionText {
		var mode = unit.get(\mode);
		var str = mode.asString;
		if (Shear.isActive(\bend, unit)) { str = str ++ " · bend " ++ FxPalette.format(unit.get(\bend), "", \pct) };
		str = str ++ " · drive " ++ FxPalette.format(unit.get(\drive), "dB", nil, 1, true)
			++ " · clip at " ++ FxPalette.format(unit.get(\clip), "dB", nil, 1);
		if (unit.get(\match)) { str = str ++ " · match" };
		if (unit.get(\safe).not) { str = str ++ " · safe off" };
		if (unit.get(\delta)) { str = str ++ " · delta" };
		^str
	}

	prDraw {
		var idc = Shear.identityColour;
		this.prCaption(this.prCaptionText, 8, 2, FxPalette.text);
		Pen.fillColor = FxPalette.card;
		Pen.fillRect(Rect(this.prSplit + 2, 22, 1, bh - 30));
		this.prDrawWords(idc);
		this.prDrawCurve(idc);
		this.prDrawCut(idc);
	}

	prDrawWords { |idc|
		var x = 8, y = 36, font = FxPalette.smallFont, cur = unit.get(\mode);
		this.prCaption("curve", 8, 20, FxPalette.text);
		wordRects = [];
		this.class.wordGroups.do { |grp, gi|
			if (gi > 0) {
				Pen.stringAtPoint("·", x @ y, font, FxPalette.dim);
				x = x + 9;
			};
			grp.do { |w|
				var s = w.asString, wd = s.bounds(font).width.ceil + 2, r = Rect(x - 1, y - 1, wd + 2, 14);
				if (w == cur) {
					Pen.fillColor = FxPalette.fade(idc, 0.22);
					Pen.addRoundedRect(r, 2, 2);
					Pen.fill;
				};
				Pen.stringAtPoint(s, x @ y, font, if (w == cur) { idc } { FxPalette.dim });
				wordRects = wordRects.add([w, r]);
				x = x + wd + 6;
			};
		};
	}

	prCurvePoints { |side|
		var r = this.prPlot, n = r.width.asInteger.max(2);
		var mode = unit.get(\mode), bend = unit.get(\bend), bias = unit.get(\bias), skew = unit.get(\skew);
		^n.collect { |px|
			var u = px.linlin(0, n - 1, -4, 4);
			(r.left + (px * r.width / (n - 1))) @ this.prY(Shear.curve(u, mode, bend, bias, skew, side).clip(-1.3, 1.3))
		}
	}

	prDrawCurve { |idc|
		var r = this.prPlot, live = this.isLive, reach, pts, pts2, now = Main.elapsedTime;
		var clipDb = unit.get(\clip), skew = unit.get(\skew), u0;
		Pen.fillColor = FxPalette.bg;
		Pen.addRoundedRect(r, 3, 3);
		Pen.fill;
		// the grid: 0, +6, +12 dB over the clip level along; -1..1 up
		Pen.width = 1;
		[-4, -2, -1, 0, 1, 2, 4].do { |u|
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, if (u == 0) { 0.9 } { 0.35 });
			Pen.line(this.prX(u) @ r.top, this.prX(u) @ r.bottom);
			Pen.stroke;
		};
		[-0.5, 0, 0.5].do { |v|
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, if (v == 0) { 0.9 } { 0.35 });
			Pen.line(r.left @ this.prY(v), r.right @ this.prY(v));
			Pen.stroke;
		};
		[[1, "0"], [2, "+6"], [4, "+12 dB"]].do { |a|
			var x = this.prX(a[0]);
			Pen.stringCenteredIn(a[1], Rect(x - 24, r.bottom + 2, 48, 12), FxPalette.smallFont, FxPalette.dim);
		};
		Pen.stringLeftJustIn("in, over the clip level", Rect(r.left, r.bottom + 2, 140, 12), FxPalette.smallFont,
			FxPalette.fade(FxPalette.dim, 0.7));
		// how far the input reaches into the curve (measured)
		reach = if (live) { unit.inLevel * (unit.get(\drive) - unit.get(\clip)).dbamp } { 0 };
		if (reach > 0.001) {
			var rr = reach.min(4.2);
			Pen.fillColor = FxPalette.fade(FxPalette.wave, 0.12);
			Pen.fillRect(Rect(this.prX(rr.neg), r.top, this.prX(rr) - this.prX(rr.neg), r.height));
		};
		// y = x, faint: where the curve leaves it is what is cut
		Pen.strokeColor = FxPalette.fade(FxPalette.dim, 0.35);
		Pen.line(this.prX(-1.25) @ this.prY(-1.25), this.prX(1.25) @ this.prY(1.25));
		Pen.stroke;
		// the curve (model); what it takes away from y = x, in orchid
		pts = this.prCurvePoints(1);
		Pen.fillColor = FxPalette.fade(idc, 0.16);
		pts.do { |p, i|
			var u = (p.x - r.left) / r.width * 8 - 4;
			var yp = p.y.clip(r.top, r.bottom);
			if (i == 0) { Pen.moveTo(p.x @ yp) } { Pen.lineTo(p.x @ yp) };
		};
		pts.reverseDo { |p|
			var u = (p.x - r.left) / r.width * 8 - 4;
			Pen.lineTo(p.x @ this.prY(u).clip(r.top, r.bottom));
		};
		Pen.fill;
		if (skew > 0.001) {
			pts2 = this.prCurvePoints(-1);
			Pen.strokeColor = FxPalette.fade(FxPalette.text, 0.6);
			Pen.width = 1.2;
			pts2.do { |p, i| if (i.even and: { i < (pts2.size - 1) }) { Pen.line(p, pts2[i + 1]) } };
			Pen.stroke;
		};
		Pen.strokeColor = FxPalette.text;
		Pen.width = 1.5;
		pts.do { |p, i| if (i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) } };
		Pen.stroke;
		// the clip level: dashed, labelled in dBFS
		Pen.strokeColor = idc;
		Pen.width = 1;
		[1, -1].do { |v| this.class.dash(r.left @ this.prY(v), r.right @ this.prY(v)) };
		Pen.stroke;
		Pen.stringRightJustIn(FxPalette.format(clipDb, "dB", nil, 1) ++ "FS", Rect(r.left, this.prY(1) - 14, r.width - 4, 12),
			FxPalette.smallFont, idc);
		Pen.stringRightJustIn(FxPalette.format(clipDb, "dB", nil, 1) ++ "FS", Rect(r.left, this.prY(-1) + 2, r.width - 4, 12),
			FxPalette.smallFont, FxPalette.fade(idc, 0.7));
		// the measured dots, fading
		dots.do { |d|
			var a = (1 - ((now - d[0]) / dotLife)).clip(0, 1);
			var p = this.prX(d[1].clip(-4.2, 4.2)) @ this.prY(d[2].clip(-1.3, 1.3));
			if (a > 0) {
				Pen.fillColor = FxPalette.fade(FxPalette.wave, a * 0.9);
				Pen.fillOval(Rect(p.x - 2, p.y - 2, 4, 4));
			};
		};
		// the highest |u| since the last reply, ridden on the curve
		u0 = if (live) { (unit.extras ? [])[0] };
		if (u0.notNil and: { u0 > 0.01 }) {
			var p = this.prX(u0.min(4.2)) @ this.prY(Shear.curve(u0, unit.get(\mode), unit.get(\bend),
				unit.get(\bias), unit.get(\skew), 1).clip(-1.3, 1.3));
			Pen.fillColor = idc;
			Pen.fillOval(Rect(p.x - 4.5, p.y - 4.5, 9, 9));
		};
	}

	prCutY { |db| var c = this.prCutRect; ^c.top + (db.linlin(-24, 24, c.height, 0, \minmax)) }
	prCutDepth { |db| var c = this.prCutRect; ^(db.linlin(-40, 0, 0, c.height * 0.4, \minmax)) }

	prDrawCut { |idc|
		var c = this.prCutRect, cw = c.width / size, live = this.isLive, txt;
		this.prCaption("cut", c.left, 20, FxPalette.text);
		Pen.fillColor = FxPalette.bg;
		Pen.addRoundedRect(c, 3, 3);
		Pen.fill;
		// the ruler: dB over the clip level
		[12, 0, -12].do { |db|
			Pen.fillColor = FxPalette.fade(FxPalette.waveDim, 0.5);
			Pen.fillRect(Rect(c.left, this.prCutY(db), c.width, 1));
		};
		// the history, oldest at the left
		if (filled > 0) {
			var xs = filled.collect { |k| c.right - ((filled - k) * cw) };
			var idx = filled.collect { |k| (pos - filled + k) % size };
			Pen.fillColor = FxPalette.fade(FxPalette.wave, 0.25);
			this.class.fillSteps(xs.collect { |x, k| [x, overs[idx[k]] !? { |o| this.prCutY(o) }] }, cw, c.bottom);
			Pen.fillColor = FxPalette.fade(idc, 0.85);
			this.class.fillSteps(xs.collect { |x, k| [x, cuts[idx[k]] !? { |ct| c.top + this.prCutDepth(ct) }] }, cw, c.top);
		};
		// the clip level across it
		Pen.strokeColor = idc;
		Pen.width = 1;
		this.class.dash(c.left @ this.prCutY(0), c.right @ this.prCutY(0));
		Pen.stroke;
		// the scales, over the history: dB over the clip level at the left, the cut's own at the right
		[12, 0, -12].do { |db|
			this.class.chip(if (db > 0) { "+" ++ db } { db.asString }, Rect(c.left + 2, this.prCutY(db) - 13, 30, 12), FxPalette.dim);
		};
		this.class.chip("clip level", Rect(c.left + 40, this.prCutY(0) + 2, 80, 12), FxPalette.fade(idc, 0.8));
		[[-40, "cut -40"], [-20, "-20"], [0, "0 dB"]].do { |a|
			var y = c.top + this.prCutDepth(a[0]);
			Pen.fillColor = FxPalette.fade(idc, 0.6);
			Pen.fillRect(Rect(c.right - 5, y, 5, 1));
			this.class.chip(a[1], Rect(c.right - 70, y + 1, 62, 12), FxPalette.fade(idc, 0.85), \right);
		};
		Pen.stringRightJustIn("now", Rect(c.left, c.bottom + 2, c.width, 12), FxPalette.smallFont, FxPalette.dim);
		Pen.stringLeftJustIn("-% s".format(seconds), Rect(c.left, c.bottom + 2, 40, 12), FxPalette.smallFont, FxPalette.dim);
		// the readout
		txt = if (live and: { this.lastOver.notNil }) {
			var o = this.lastOver, h = this.lastHeat ? 1;
			var ostr = if (o >= 0) { FxPalette.fixed(o, 1) ++ " dB over" } { FxPalette.fixed(o.neg, 1) ++ " dB under" };
			ostr ++ " · cut " ++ (if (this.lastCut <= -99) { "-inf" } { FxPalette.fixed(this.lastCut, 0) ++ " dB" })
				++ " · heat " ++ FxPalette.format(h.max(1e-6).ampdb, "dB", nil, 1, true)
		} { "no sound: the history fills while sound passes" };
		Pen.stringLeftJustIn(txt, Rect(c.left, bh - 16, c.width, 13), FxPalette.smallFont, FxPalette.text);
	}

	// ------------------------------------------------------------ gestures

	prMouseDown { |x, y, mod, count|
		var w = this.prWordAt(x, y), side = this.prNearClip(x, y);
		dragShift = mod.isShift;
		drag = nil;
		if (w.notNil) { unit.set(\mode, w); ^this };
		if (this.prCutRect.containsPoint(x @ y)) { if (count == 2) { this.clear }; ^this };
		if (this.prPlot.containsPoint(x @ y).not) { ^this };
		if (side.notNil) {
			if (count == 2) { unit.set(\clip, Shear.param(\clip)[\default]); ^this };
			drag = \clip;
			dragSign = side;
		} {
			if (count == 2) { unit.set(\drive, Shear.param(\drive)[\default]); ^this };
			drag = \drive;
			dragSign = 1;
		};
		dragFrom = unit.get(drag);
		dragY = y;
	}

	prMouseMove { |x, y, mod|
		var spec;
		if (drag.isNil) { ^this };
		if (mod.isShift != dragShift) { dragShift = mod.isShift; dragFrom = unit.get(drag); dragY = y };
		spec = Shear.param(drag)[\spec];
		unit.set(drag, (dragFrom + ((dragY - y) * dragSign / 200 * (spec.maxval - spec.minval)
			* if (dragShift) { 0.1 } { 1 })).clip(spec.minval, spec.maxval));
	}

	prMouseUp { drag = nil }

	prWheel { |x, y, delta|
		var key;
		if (this.prPlot.containsPoint(x @ y).not) { ^this };
		key = if (wheelMod.notNil and: { wheelMod.isAlt }) { \bias } { \bend };
		unit.set(key, (unit.get(key) + (delta.sign * 0.02)).clip(Shear.param(key)[\spec].minval, 1));
	}

	hint { |x, y|
		var w, side;
		if (unit.isNil) { ^"" };
		if (x.isNil) { ^"the curve: drag for drive, drag the dashed line for the clip level, the wheel for bend" };
		w = this.prWordAt(x, y);
		side = this.prNearClip(x, y);
		^case
		{ w.notNil } { "mode % - click to choose it (%)".format(w, if (w == unit.get(\mode)) { "now" } { "now " ++ unit.get(\mode) }) }
		{ side.notNil } {
			"the curve is drawn at the clip level (now %FS): drag to move the level, the reach shows how far the signal goes past it - double click: -6 dB"
			.format(FxPalette.format(unit.get(\clip), "dB", nil, 1))
		}
		{ this.prPlot.containsPoint(x @ y) } {
			"the curve (the settings), the dots where the sound sat on it: drag up and down for drive (now %, shift: fine), the wheel for bend, alt + wheel for bias - double click: drive 6 dB"
			.format(FxPalette.format(unit.get(\drive), "dB", nil, 1, true))
		}
		{ this.prCutRect.containsPoint(x @ y) } {
			"measured: blue how far the input goes past the clip level, orchid how loud what was cut is against the input (top, -40..0 dB) - double click to clear"
		}
		{ true } { "smooth · bend · drive · clip at: what the curve is set to now" }
	}
}
