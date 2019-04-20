/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.util.*;
import haven.render.*;
import haven.RenderContext.PostProcessor;
import haven.render.Texture2D.Sampler2D;

public abstract class PView extends Widget {
    public final RenderTree tree;
    public final RenderTree.Slot conf;
    public final RenderTree.Slot basic;
    public Texture2D fragcol = null;
    public Texture2D depth = null;
    private final Map<Object, Pipe.Op> basicstates = new IdentityHashMap<>();
    private final Light.LightList lights = new Light.LightList();
    private final ScreenList list2d = new ScreenList();
    private final TickList ticklist = new TickList();
    private Sampler2D fragsamp;
    private DrawList back = null;

    public PView(Coord sz) {
	super(sz);
	tree = new RenderTree();
	tree.add(list2d, Render2D.class);
	tree.add(ticklist, TickList.TickNode.class);
	conf = tree.add((RenderTree.Node)null);
	conf.ostate(frame());
	basic = conf.add((RenderTree.Node)null);
	basic();
    }

    public static class WidgetContext extends RenderContext {
	private final Widget wdg;

	public WidgetContext(Widget wdg) {
	    this.wdg = wdg;
	}

	public Widget widget() {
	    return(wdg);
	}
    }

    private final WidgetContext ctx = new WidgetContext(this);
    private Pipe.Op conf() {
	return(Pipe.Op.compose(new FrameConfig(this.sz), ctx));
    }

    private Pipe.Op curconf = null;
    private Pipe.Op curconf() {
	if(curconf == null)
	    curconf = conf();
	return(curconf);
    }

    private Pipe.Op frame() {
	return(Pipe.Op.compose(curconf(), new FrameInfo()));
    }

    private void reconf() {
	curconf = null;
	conf.ostate(frame());
    }

    public void resize(Coord sz) {
	super.resize(sz);
	reconf();
    }

    public void basic(Object id, Pipe.Op state) {
	Pipe.Op prev;
	if(state == null)
	    prev = basicstates.remove(id);
	else
	    prev = basicstates.put(id, state);
	if(!Utils.eq(prev, state)) {
	    basic.ostate(p -> {
		    for(Pipe.Op op : basicstates.values())
			op.apply(p);
		});
	}
    }

    /* XXX? Remove standard clearing and assume implementations to add
     * explicit clearing slot? */
    protected FColor clearcolor() {
	return(FColor.BLACK);
    }

    /* I've no idea why this function is necessary. */
    @SuppressWarnings("unchecked")
    private static RenderList.Slot<Rendered> uglyJavaCWorkAround(RenderList.Slot<?> slot) {
	return((RenderList.Slot<Rendered>)slot);
    }

    public void tick(double dt) {
	super.tick(dt);
	conf.ostate(frame());
	ticklist.tick(dt);
    }

    private GOut resolveout(GOut def, PostProcessor next) {
	if(next == null)
	    return(def);
	if((next.buf != null) && !next.buf.tex.sz().equals(fragcol.sz())) {
	    next.buf.dispose();
	    next.buf = null;
	}
	if(next.buf == null) {
	    Texture2D tex = new Texture2D(fragcol.sz(), DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8), null);
	    next.buf = new Sampler2D(tex);
	    next.buf.swrap(Texture.Wrapping.CLAMP).twrap(Texture.Wrapping.CLAMP);
	}
	Pipe st = new BufPipe();
	Area area = Area.sized(Coord.z, next.buf.tex.sz());
	st.prep(new FrameInfo()).prep(new States.Viewport(area)).prep(new Ortho2D(area));
	st.prep(new FragColor<>(next.buf.tex.image(0)));
	return(new GOut(def.out, st, new Coord(area.sz())));
    }

    protected void resolve(GOut g) {
	Iterator<PostProcessor> post = ctx.postproc().iterator();
	PostProcessor next = post.hasNext() ? post.next() : null;
	resolveout(g, next).image(new TexRaw(fragsamp, true), Coord.z);
	while(next != null) {
	    PostProcessor cur = next;
	    next = post.hasNext() ? post.next() : null;
	    cur.run(resolveout(g, next), cur.buf);
	}
    }

    public void add(PostProcessor post) {ctx.add(post);}
    public void remove(PostProcessor post) {ctx.remove(post);}

    public void draw(GOut g) {
	ticklist.gtick(g.out);
	if((back == null) || !back.compatible(g.out.env())) {
	    if(back != null) {
		back.dispose();
		tree.remove(back);
	    }
	    back = g.out.env().drawlist();
	    back.asyncadd(tree, Rendered.class);
	}
	lights();
	FColor cc = clearcolor();
	if(cc != null)
	    g.out.clear(basic.state(), FragColor.fragcol, cc);
	g.out.clear(basic.state(), 1.0);
	back.draw(g.out);
	resolve(g);
	list2d.draw(g);
    }

    private static final Object id_fb = new Object(), id_view = new Object(), id_misc = new Object();
    protected void basic() {
	basic(id_fb, p -> {
		FrameConfig fb = p.get(FrameConfig.slot);
		if((fragcol == null) || !fragcol.sz().equals(fb.sz)) {
		    if(fragcol != null)
			fragcol.dispose();
		    fragcol = new Texture2D(fb.sz, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8), null);
		    fragsamp = new Sampler2D(fragcol);
		}
		if((depth == null) || !depth.sz().equals(fb.sz)) {
		    if(depth != null)
			depth.dispose();
		    depth = new Texture2D(fb.sz, DataBuffer.Usage.STATIC, Texture.DEPTH, new VectorFormat(1, NumberFormat.FLOAT32), null);
		}
		p.prep(new FragColor<>(fragcol.image(0))).prep(new DepthBuffer<>(depth.image(0)));
	    });
	basic(id_view, p -> {
		FrameConfig fb = p.get(FrameConfig.slot);
		Area area = Area.sized(Coord.z, fb.sz);
		p.prep(new States.Viewport(area));
		p.prep(Homo3D.state);
	    });
	basic(id_misc, Pipe.Op.compose(new States.Blending(States.Blending.Function.ADD, States.Blending.Factor.SRC_ALPHA, States.Blending.Factor.INV_SRC_ALPHA,
							   States.Blending.Function.MAX, States.Blending.Factor.SRC_ALPHA, States.Blending.Factor.INV_SRC_ALPHA),
				       new States.Depthtest(States.Depthtest.Test.LE),
				       new States.Facecull()));
	lights();
    }

    protected void lights() {
	basic(Light.class, Pipe.Op.compose(lights, lights.compile()));
    }

    public interface Render2D extends RenderTree.Node {
	public void draw(GOut g, Pipe state);
    }

    public static class ScreenList implements RenderList<Render2D> {
	private final Set<Slot<? extends Render2D>> cur = new HashSet<>();

	public void draw(GOut g) {
	    for(Slot<? extends Render2D> slot : cur) {
		slot.obj().draw(g, slot.state());
	    }
	}

	public void add(Slot<? extends Render2D> slot) {
	    cur.add(slot);
	}

	public void remove(Slot<? extends Render2D> slot) {
	    cur.remove(slot);
	}

	public void update(Slot<? extends Render2D> slot) {}
	public void update(Pipe group, int[] statemask) {}
    }
}
