// =====================================================================
// PeakKit - what Shear and Wall share: the curves, their antiderivatives,
// the antialiasing, an exact delay
//
// Class methods only. Every curve is written once, as arithmetic that runs on
// Numbers and on UGens alike, so a window draws the same numbers the server
// plays:
//
//   PeakKit.f(\smooth, 1.2, 0.3);            // the curve at u = 1.2 (1 is the clip level)
//   PeakKit.fInt(\smooth, 1.2, 0.3);         // its antiderivative (F)
//   PeakKit.curveNames;
//   { PeakKit.linearTwin(SinOsc.ar(440)) }.plot;   // what the antialiasing does to a line
//
// The antialiasing is first-order ADAA in Vicanek's transparent form: a one
// pole before the curve, the divided difference of the antiderivative over
// half steps centred on the last sample, a one pole after it. Where the curve
// is a line, the three together are a first-order allpass: flat, about one
// sample late, and `linearTwin` is that allpass alone, so a dry signal can be
// lined up with the wet to the last bit.
//
// Six of the ten curves (smooth, crisp, inflate, sine, gap, rectify) are one
// piecewise polynomial whose numbers a mode picks (`polyCoefs`); tube, diode,
// fold and wrap have their own. `bank` evaluates the five at once, weighted.
// =====================================================================

PeakKit {
	classvar <buchlaNorm, <sineP3, <sineP5, <tpExtra;

	*initClass {
		var c = 0.5pi;
		// the only odd quintic with q(pi/2) = 1 and q'(pi/2) = 0
		sineP5 = (-1 - (3 * (1 - c) / c)) / (2 * c.pow(4));
		sineP3 = (-1 - (5 * sineP5 * c.pow(4))) / (3 * c * c);
		// the Buchla cell's largest output over its valid range, |v| <= 10
		buchlaNorm = 0;
		(0..20000).do { |j| buchlaNorm = max(buchlaNorm, this.prVout((j / 1000) - 10).abs) };
		tpExtra = [0.25, 0.5, 0.75].collect { |fr| this.prFracSample(5 + fr) };
	}

	// ----------------------------------------------------------- the names

	*curveNames { ^#[\smooth, \crisp, \inflate, \tube, \sine, \diode, \fold, \wrap, \gap, \rectify] }

	// the five evaluators, and which one plays each curve
	*families { ^#[\poly, \tube, \diode, \fold, \wrap] }
	*familyOf { ^#[0, 0, 0, 1, 0, 2, 3, 4, 0, 0] }
	*isPoly { ^#[1, 1, 1, 0, 1, 0, 0, 0, 1, 1] }
	*polyMembers { ^#[\smooth, \crisp, \inflate, \sine, \gap, \rectify] }
	// a curve's place among the poly members (0 for the others: never read)
	*polyIndexOf { ^#[0, 1, 2, 0, 3, 0, 0, 0, 4, 5] }

	*modeIndex { |mode| ^this.curveNames.indexOf(mode.asSymbol) }

	// ------------------------------------------------------ the curves: f, F

	// f and F of a curve by name, at u (clip level = 1) and bend beta, for
	// Numbers or UGens (beta may be a kr UGen; mode is a Symbol)
	*f { |mode, u, beta = 0|
		^switch (mode.asSymbol,
			\tube, { this.tubef(u) },
			\diode, { this.diodef(u, this.diodeL(beta)) },
			\fold, { this.foldf(u, this.foldA(beta)) },
			\wrap, { this.wrapf(u, beta) },
			{ this.polyf(u, this.polyPrep(this.polyCoefs(mode, beta))) }
		)
	}

	*fInt { |mode, u, beta = 0|
		^switch (mode.asSymbol,
			\tube, { this.tubeF(u) },
			\diode, { this.diodeF(u, this.diodeL(beta)) },
			\fold, { this.foldF(u, this.foldA(beta)) },
			\wrap, { this.wrapF(u, beta) },
			{ this.polyF(u, this.polyPrep(this.polyCoefs(mode, beta))) }
		)
	}

	// with an offset b along the curve: (f(u + b) - f(b)) / (1 + |f(b)|) - still
	// 0 at 0 and never past 1 - and its antiderivative
	*fB { |mode, u, beta = 0, b = 0|
		var fb = this.f(mode, b, beta);
		^(this.f(mode, u + b, beta) - fb) / (1 + fb.abs)
	}

	*fIntB { |mode, u, beta = 0, b = 0|
		var fb = this.f(mode, b, beta);
		^(this.fInt(mode, u + b, beta) - (fb * u)) / (1 + fb.abs)
	}

	// --------------------------------------------- the poly family (generic)

	// [a1, a2, p1, p2, p3, p4, p5, c1, c2, h, sNeg]: a first piece
	// P(a) = p1 a + ... + p5 a^5 up to a1, a middle piece from a1 to a2,
	// M = c1 t + c2 t^2 + h (sqrt(a) - sqrt(a1)) with t = (a - a1) / (a2 - a1),
	// then 1; the negative side scaled by sNeg
	*polyCoefs { |mode, beta = 0|
		var k, t, c, aa, bb, cc, dd;
		^switch (mode.asSymbol,
			\smooth, {
				k = beta.max(0.002);
				[1 - k, 1 + k, 1, 0, 0, 0, 0, 2 * k, k.neg, 0, 1]
			},
			\crisp, {
				t = (-1 - (11 * beta)).dbamp;
				[t, t.reciprocal, 1, 0, 0, 0, 0, 0, 0, t.sqrt, 1]
			},
			\inflate, {
				c = (100 * beta) - 50;
				aa = 1.5 + (c / 100); bb = c.neg / 50; cc = (c / 100) - 0.5;
				dd = 0.0625 - (0.0025 * c) + (0.000025 * c * c);
				[1, 1, aa, bb - dd, cc + (2 * dd), dd.neg, 0, 0, 0, 0, 1]
			},
			\sine, { [0.5pi, 0.5pi, 1, 0, sineP3, 0, sineP5, 0, 0, 0, 1] },
			\gap, {
				t = 0.5 * beta;
				[t, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1]
			},
			\rectify, { [1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1 - (2 * beta)] },
			{ Error("PeakKit: % is not a poly curve".format(mode)).throw }
		)
	}

	// t (0..1 across the middle piece) is clipped: exact for any member, and at
	// kr in a graph iw is interpolated across a block, which during a morph out
	// of a member without a middle (iw 1e9) made t huge and f 1e20 (measured)
	// what the evaluator reads, worked out once from the coefficients (kr in a graph).
	// lin1 = P(a1) - h sqrt(a1): the middle piece's value at a1, which f carries
	// as P(min(a, a1)) and F has to integrate over the middle
	*polyPrep { |c|
		var a1 = c[0], a2 = c[1], p = c[2..6], w = a2 - a1;
		var pa1 = a1 * (p[0] + (a1 * (p[1] + (a1 * (p[2] + (a1 * (p[3] + (a1 * p[4]))))))));
		^(
			a1: a1, a2: a2, p: p, lin1: pa1 - (c[9] * a1.sqrt),
			q: p.collect { |pk, i| pk / (i + 2) },
			c1: c[7], c2: c[8], h: c[9], sNeg: c[10],
			c1h: c[7] * 0.5, c2t: c[8] / 3, h23: c[9] * (2 / 3),
			w: w, iw: w.max(1e-9).reciprocal, sa1: a1.sqrt, a115: a1 * a1.sqrt
		)
	}

	*prSideK { |u, k| ^k[\sNeg] + ((1 - k[\sNeg]) * u.sign.max(0)) }

	*polyf { |u, k|
		var a = u.abs, lo = a.min(k[\a1]), mid = a.clip(k[\a1], k[\a2]);
		var t = ((mid - k[\a1]) * k[\iw]).clip(0, 1), p = k[\p];
		var pp = lo * (p[0] + (lo * (p[1] + (lo * (p[2] + (lo * (p[3] + (lo * p[4]))))))));
		var mm = (t * (k[\c1] + (t * k[\c2]))) + (k[\h] * (mid.sqrt - k[\sa1]));
		^(pp + mm) * u.sign * this.prSideK(u, k)
	}

	*polyF { |u, k|
		var a = u.abs, lo = a.min(k[\a1]), mid = a.clip(k[\a1], k[\a2]);
		var t = ((mid - k[\a1]) * k[\iw]).clip(0, 1), q = k[\q];
		var pint = lo * lo * (q[0] + (lo * (q[1] + (lo * (q[2] + (lo * (q[3] + (lo * q[4]))))))));
		var mi = (k[\w] * t * t * (k[\c1h] + (t * k[\c2t]))) + (k[\h23] * ((mid * mid.sqrt) - k[\a115]))
			+ (k[\lin1] * (mid - k[\a1]));
		var top = a.max(k[\a2]) - k[\a2];
		^(pint + mi + top) * this.prSideK(u, k)
	}

	// ------------------------------------------------------------- the others

	*tubef { |u| ^u.tanh }
	*tubeF { |u| var a = u.abs; ^a + ((a * -2).exp + 1).log - 2.log }

	*diodeL { |beta| ^1 - (0.7 * beta) }
	*diodef { |u, ll|
		var p = u.max(0), n = u.min(0);
		^(1 - p.neg.exp) - (ll * (1 - (n / ll).exp))
	}
	*diodeF { |u, ll|
		var p = u.max(0), n = u.min(0);
		^(p + p.neg.exp - 1) + ((ll.neg * n) + (ll * ll * ((n / ll).exp - 1)))
	}

	*wrapf { |u, beta| ^((1 - beta) * u.wrap2(1)) + (beta * u.fold2(1)) }
	*wrapF { |u, beta|
		var w = u.wrap2(1), g = u.fold2(1), s = (((u + 1) % 4) - 2).sign.max(0);
		^((1 - beta) * w * w * 0.5) + (beta * ((g * g * 0.5) + (s * (1 - (g * g)))))
	}

	// the Buchla 259 timbre circuit (Esqueda et al., DAFx-17, eqs. 13-18): five
	// folding cells, each slope * excess(v, t), valid to +-10 V
	*foldT { ^#[0.6, 2.994, 5.46, 1.8, 4.08] }
	*foldW { ^#[-10.0, -10.466, -6.062, 10.135, 9.720] }
	*foldA { |beta| ^1 + (9 * beta) }

	*prVout { |v|
		var o = v * 5;
		this.foldT.do { |t, i| o = o + (this.foldW[i] * v.excess(t)) };
		^o
	}
	*prG { |v|
		var g = v * v * 2.5;
		this.foldT.do { |t, i| var e = v.excess(t); g = g + (this.foldW[i] * e * e * 0.5) };
		^g
	}
	*foldf { |u, aa| ^this.prVout((aa * u).clip2(10)) / buchlaNorm }
	*foldF { |u, aa|
		var v = (aa * u).clip2(10);
		^(this.prG(v) + (this.prVout(v) * ((aa * u) - v))) / aa / buchlaNorm
	}

	// fold's divided difference (F(p) - F(q)) / (p - q), factored so that no
	// two large numbers are subtracted: G's terms reach about 900 at |v| = 10.
	// ill (0 or 1, a UGen in a graph) says p and q are too close: the answer is
	// then finite but meaningless, and the caller takes f at the midpoint
	*foldDD { |p, q, aa, ill = 0|
		var vp = (aa * p).clip2(10), vq = (aa * q).clip2(10);
		var dd = aa * (p - q), ddg = dd + (ill * (1 - dd));
		var dv = vp - vq, tiny = (1e-7 - dv.abs).sign.max(0), dvg = dv + (tiny * (1 - dv));
		var r = dv / ddg, eq = (aa * q) - vq, outp = this.prVout(vp), outq = this.prVout(vq);
		var g = (vp + vq) * 2.5;
		this.foldT.do { |t, i|
			var s = 1 - ((vp.clip2(t) - vq.clip2(t)) / dvg);
			g = g + (this.foldW[i] * s * (vp.excess(t) + vq.excess(t)) * 0.5);
		};
		^((r * g) + (outp * (1 - r)) + ((outp - outq) * eq / ddg)) / buchlaNorm
	}

	// ------------------------------------------------------ the antialiasing

	*apCoef { ^3 - 8.sqrt }		// 0.171573: 1 / (3 + sqrt 8)

	// Vicanek's two one-poles, before and after the curve
	*pre { |x| var a = this.apCoef; ^FOS.ar(x, 1 + a, 0, a.neg) }
	*post { |x| var a = this.apCoef; ^FOS.ar(x, 1 + a, 0, a.neg) }

	// the chain with the curve a line: a first-order allpass, the dry's twin
	*linearTwin { |x| ^this.twinOfPre(this.pre(x)) }

	// the same, from a signal already through `pre` (shares the one pole)
	*twinOfPre { |p| ^this.post((p + (Delay1.ar(p) * 6) + Delay2.ar(p)) * 0.125) }

	// transparent first-order ADAA of one curve, between pre and post (x is
	// already through `pre`): fF and ff are { |u| } for F and f. mv (0..1) fades
	// over to the fallback, f at the midpoints. Answers what goes into `post`
	*adaa { |x, fF, ff, eps = 0.01, mv = 0|
		var x1 = Delay1.ar(x), m = (x + x1) * 0.5;
		var fx = fF.(x), fm = fF.(m), fp = ff.((m + x1) * 0.5), fq = ff.((x + m) * 0.5);
		^this.prTail(x, x1, m, fx, fm, fp, fq, 0, 0, 0, eps, mv)
	}

	// the shared tail: A over (m, x1) and over (x1, m1), each falling back to f
	// at its midpoint when its points are closer than eps, and faded by mv.
	// dd1 and dd2 are a divided difference supplied whole (fold's), weighted by wdd
	*prTail { |x, x1, m, fx, fm, fp, fq, dd1, dd2, wdd, eps, mv|
		var d1 = m - x1, ill1 = d1.abs < eps, d2 = Delay1.ar(d1), ill2 = Delay1.ar(ill1);
		var fx1 = Delay1.ar(fx), fq1 = Delay1.ar(fq);
		var a1 = Select.ar(ill1, [((fm - fx1) / (d1 + (ill1 * (1 - d1)))) + (dd1 * wdd), fp]);
		var a2 = Select.ar(ill2, [((fx1 - Delay1.ar(fm)) / (d2 + (ill2 * (1 - d2)))) + (dd2 * wdd), fq1]);
		if (mv != 0) {
			a1 = a1 + ((fp - a1) * mv);
			a2 = a2 + ((fq1 - a2) * mv);
		};
		^(a1 + a2) * 0.5
	}

	// the five evaluators at once, weighted (k: an Event of kr signals built
	// before any audio, see Shear.prKr): w (5 weights), pp (polyPrep), ll
	// (diode), aa (fold), wb (wrap), b (the offset), fb and nInv (its
	// correction), mv, eps. x is through `pre`; answers what goes into `post`.
	// Each quantity is built family after family and consumed at once, so few
	// audio wires are alive at a time
	*bank { |x, k|
		var w = k[\w], pp = k[\pp], ll = k[\ll], aa = k[\aa], wb = k[\wb], eps = k[\eps];
		var xb = x + k[\b], x1 = Delay1.ar(xb), m = (xb + x1) * 0.5;
		var sumF, sumf, fx, fm, fp, fq, fd;
		sumF = { |u|
			var acc = this.polyF(u, pp) * w[0];
			acc = this.tubeF(u).madd(w[1], acc);
			acc = this.diodeF(u, ll).madd(w[2], acc);
			this.wrapF(u, wb).madd(w[4], acc)
		};
		sumf = { |u|
			var acc = this.polyf(u, pp) * w[0];
			acc = this.tubef(u).madd(w[1], acc);
			acc = this.diodef(u, ll).madd(w[2], acc);
			acc = this.foldf(u, aa).madd(w[3], acc);
			this.wrapf(u, wb).madd(w[4], acc)
		};
		fx = sumF.(xb);
		fm = sumF.(m);
		fp = sumf.((m + x1) * 0.5);
		fq = sumf.((xb + m) * 0.5);
		fd = this.prFoldPairs(xb, x1, m, aa, eps);
		^(this.prTail(xb, x1, m, fx, fm, fp, fq, fd[0], fd[1], w[3], eps, k[\mv]) - k[\fb]) * k[\nInv]
	}

	// fold's two divided differences for the bank, over (m, x1) and (x1, m1),
	// the second one a sample late of (x, m): foldDD's arithmetic with the
	// cells shared between the points, one threshold at a time
	*prFoldPairs { |xb, x1, m, aa, eps|
		var nn = buchlaNorm.reciprocal;
		var vx = (aa * xb).clip2(10), vm = (aa * m).clip2(10), vx1 = Delay1.ar(vx);
		var guard = { |d| d + ((1e-7 - d.abs).sign.max(0) * (1 - d)) };
		var d1 = m - x1, d2 = xb - m;
		var ill1 = d1.abs < eps, ill2 = d2.abs < eps;
		var dd1 = aa * d1, dd2 = aa * d2;
		var iD1 = (dd1 + (ill1 * (1 - dd1))).reciprocal, iD2 = (dd2 + (ill2 * (1 - dd2))).reciprocal;
		var dv1 = vm - vx1, dv2 = vx - vm;
		var idv1 = guard.(dv1).reciprocal, idv2 = guard.(dv2).reciprocal;
		var r1 = dv1 * iD1, r2 = dv2 * iD2;
		var ox = vx * 5, om = vm * 5, g1 = (vm + vx1) * 2.5, g2 = (vx + vm) * 2.5, ox1;
		this.foldT.do { |t, i|
			var wh = this.foldW[i] * 0.5;
			var cx = vx.clip2(t), ex = vx.excess(t), cm = vm.clip2(t), em = vm.excess(t);
			var cx1 = Delay1.ar(cx), ex1 = Delay1.ar(ex);
			ox = ex.madd(this.foldW[i], ox);
			om = em.madd(this.foldW[i], om);
			g1 = ((em + ex1) * (1 - ((cm - cx1) * idv1))).madd(wh, g1);
			g2 = ((ex + em) * (1 - ((cx - cm) * idv2))).madd(wh, g2);
		};
		ox1 = Delay1.ar(ox);
		^[
			((r1 * g1) + (om * (1 - r1)) + ((om - ox1) * ((aa * x1) - vx1) * iD1)) * nn,
			Delay1.ar(((r2 * g2) + (ox * (1 - r2)) + ((ox - om) * ((aa * m) - vm) * iD2)) * nn)
		]
	}

	// ------------------------------------------------------------- the delay

	// exactly k samples late. DelayN truncates delaytime * SR, and k / SR lands
	// on k - 1 for some k (7, 14, 23, 28 at 44.1 kHz), so half a sample more;
	// k = 0 is x itself (DelayN at 0 gives 0 samples, at 0.5 / SR it gives 1)
	*delay { |x, k, maxSec = 0.02|
		var d;
		if (k.isNumber) {
			^if (k <= 0) { x } { DelayN.ar(x, maxSec, (k + 0.5) * SampleDur.ir) }
		};
		d = DelayN.ar(x, maxSec, (k + 0.5) * SampleDur.ir);
		^Select.ar(k > 0, [x, d])
	}

	// The audio input waits for every kr value: a kr zero built from all of
	// them is added to it. sclang's sort walks depth first from whatever is
	// ready, and the input (In.ar, made before *process) is ready first, so
	// without this the audio chains start before their kr inputs exist and
	// run side by side, holding their partial sums: more audio wire buffers
	// than a stock server has (64). k is anything holding kr UGens
	*afterKr { |sig, p, k|
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

	// ------------------------------------------------- true peak (BS.1770)

	// ITU-R BS.1770-5 Annex 2: the 4x oversampling FIR, 12 taps a phase
	*tpPhase0 {
		^#[0.0017089843750, 0.0109863281250, -0.0196533203125, 0.0332031250000, -0.0594482421875, 0.1373291015625,
			0.9721679687500, -0.1022949218750, 0.0476074218750, -0.0266113281250, 0.0148925781250, -0.0083007812500]
	}
	*tpPhase1 {
		^#[-0.0291748046875, 0.0292968750000, -0.0517578125000, 0.0891113281250, -0.1665039062500, 0.4650878906250,
			0.7797851562500, -0.2003173828125, 0.1015625000000, -0.0582275390625, 0.0330810546875, -0.0189208984375]
	}
	*tpPhases { ^[this.tpPhase0, this.tpPhase1, this.tpPhase1.reverse, this.tpPhase0.reverse] }

	// The four phases sit at about 5.83, 5.69, 5.31 and 5.17 samples (their DC
	// group delays): nothing reads the middle, and a sine at SR/6 or SR/3 read
	// up to 0.295 dB low and came out of the limiter that much over its
	// ceiling (measured). Three more points, windowed sincs (Kaiser, beta 6)
	// on the same 12 taps at 5.25, 5.5 and 5.75, bring the worst under-reading
	// from 1 to 19.5 kHz at 48 kHz to 0.073 dB (one point at 5.5 alone left
	// 16 kHz 0.294 dB low; measured on a 64-phase grid)
	*prFracSample { |c|
		var i0 = { |x| var s = 1, t = 1; (1..30).do { |k| t = t * (x / 2 / k).squared; s = s + t }; s };
		var h = 12.collect { |i|
			var t = i - c;
			sin(pi * t) / (pi * t) * (i0.(6 * (1 - (t / 6.5).squared).max(0).sqrt) / i0.(6))
		};
		^h / h.sum
	}
	*tpAllPoints { ^this.tpPhases ++ tpExtra }

	// x and 11 Delay1 after it: taps[i] is x i samples ago (exact, never DelayN)
	*tpTaps { |x| var t = [x]; 11.do { t = t.add(Delay1.ar(t.last)) }; ^t }

	// the sample peak with the true peak's latency: between taps 5 and 6
	*samplePeakOf { |taps| ^max(taps[5].abs, taps[6].abs) }

	// the seven interpolated points between taps 6 and 5 (BS.1770's four and
	// the three windowed sincs), as |value|, each a madd chain (Number taps work too:
	// the check's reference)
	*tpPoints { |taps|
		^this.tpAllPoints.collect { |c|
			var acc = taps[0] * c[0];
			(1..11).do { |i| acc = (taps[i] * c[i]) + acc };	// a MulAdd in a graph (the optimiser)
			acc.abs
		}
	}

	// the true peak of x, about 5.5 samples late: the sample peak of taps 5
	// and 6 and the seven points between them
	*truePeak { |x, taps|
		taps = taps ?? { this.tpTaps(x) };
		^this.tpPoints(taps).inject(this.samplePeakOf(taps), { |a, b| max(a, b) })
	}

	// ------------------------------------------------------ the window max

	// the largest of d over the last w samples, exactly, without a windowed
	// max UGen: a sparse table M_0 = d, M_j+1 = max(M_j, M_j 2^j samples ago),
	// then two overlapping windows of 2^k (k = floor(log2 w)) that cover
	// [n - w + 1, n]. levels: M_0 .. M_(maxK - 1) (11 covers 6 ms at 192 kHz).
	// ws: window lengths (Numbers or ir UGens). Answers one signal per w
	*windowMax { |d, ws, maxK = 11|
		var m = [d];
		(maxK - 1).do { |j|
			var len = 2 ** j;
			var del = if (j == 0) { Delay1.ar(m[j]) } {
				DelayN.ar(m[j], len / 44100 + 0.001, (len + 0.5) * SampleDur.ir)
			};
			m = m.add(max(m[j], del));
		};
		^ws.asArray.collect { |w|
			var k = (w + 0.5).log2.floor, mk = Select.ar(k, m);
			var rest = (w - (2 ** k)).max(1);
			max(mk, DelayN.ar(mk, 0.007, (rest + 0.5) * SampleDur.ir))
		}
	}

	// ------------------------------------------------ short-term loudness

	// BS.1770's K-weighting as two RBJ biquads: a +4 dB shelf at 1682 Hz
	// (slope 1, Q 0.707) and a 38 Hz high pass (Q 0.5); within about 0.1 dB
	// of the standard's filters in the audio band, at any sample rate
	*kWeight { |x| ^BHiPass.ar(BHiShelf.ar(x, 1681.97, 1, 4), 38.135, 1.9988) }

	// short-term loudness in LUFS (approx.): K-weighted, squared, summed over
	// channels (all weighted 1), a block's sum read once a block, then a
	// running sum of `seconds` worth of blocks: -0.691 + 10 log10(mean). No
	// gating (short-term has none). kr
	*shortTermLoudness { |sig, seconds = 3|
		var sq = sig.asArray.collect { |x| this.kWeight(x).squared }.sum;
		var blk = A2K.kr(RunningSum.ar(sq, BlockSize.ir));
		var nb = (seconds * ControlRate.ir).round;
		var mean = RunningSum.kr(blk, nb) / (nb * BlockSize.ir);
		^(mean.max(1e-12).log10 * 10) - 0.691
	}
}
