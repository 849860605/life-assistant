<template>
  <view class="fb">
    <!-- 标题 -->
    <view class="hero">
      <view>
        <view class="hero-title">{{ trip ? `${form.destination}之旅 · ` : "" }}机票智能比价</view>
        <view class="hero-sub">飞猪、携程、美团同时查，按航班号合并比价<text v-if="trip">，按你的行程（{{ trip.title }}）挑时间合适的航班</text></view>
      </view>
      <text v-if="trip" class="unlink" @click="unlink">不关联行程</text>
    </view>

    <!-- 从别处进来、有即将出发的行程：问一句要不要按行程填好 -->
    <view v-if="suggestTrip && !trip" class="suggest">
      <Icon name="trip" :size="16" color="var(--accent)" />
      <text class="suggest-text">你有行程「{{ suggestTrip.title }}」{{ suggestTrip.when }}，按它填好出发地、目的地和日期？</text>
      <view class="suggest-acts">
        <text class="suggest-btn" @click="useSuggested">按行程填</text>
        <text class="suggest-no" @click="suggestTrip = null">不用</text>
      </view>
    </view>

    <!-- 搜索 -->
    <view class="panel search">
      <view class="modes">
        <view v-for="m in MODES" :key="m.key" class="mode" :class="{ on: mode === m.key }" @click="setMode(m.key)"><view class="radio" />{{ m.label }}</view>
      </view>
      <view class="sbar">
        <view class="sfield city-f">
          <text class="sk">出发地</text>
          <view class="city-row"><input id="fb-origin" v-model="form.origin" class="sv" placeholder="出发城市" /><text v-if="codes.origin" class="code">{{ codes.origin }}</text></view>
        </view>
        <view class="swap" @click="swapCities"><Icon name="swap" :size="16" color="var(--accent)" /></view>
        <view class="sfield city-f">
          <text class="sk">目的地</text>
          <view class="city-row"><input id="fb-destination" v-model="form.destination" class="sv" placeholder="到达城市" /><text v-if="codes.destination" class="code">{{ codes.destination }}</text></view>
        </view>
        <picker mode="date" :value="form.date" :start="today" @change="setDate($event.detail.value)">
          <view class="sfield"><text class="sk">出发日期</text><text class="sv">{{ cnDay(form.date) }}</text></view>
        </picker>
        <picker v-if="mode === 'round'" mode="date" :value="form.back || form.date" :start="form.date" @change="form.back = $event.detail.value">
          <view class="sfield"><text class="sk">返程日期</text><text class="sv">{{ form.back ? cnDay(form.back) : "选日期" }}</text></view>
        </picker>
        <view class="sfield pax-f" @click.stop="paxOpen = !paxOpen">
          <text class="sk">乘客</text>
          <text class="sv">{{ pax.adult }}成人 · {{ pax.child }}儿童 · {{ pax.infant }}婴儿</text>
          <view v-if="paxOpen" class="pop pax-pop" @click.stop>
            <view v-for="p in PAX" :key="p.key" class="pax-row">
              <text>{{ p.label }}<text class="muted small"> {{ p.hint }}</text></text>
              <view class="stepper">
                <text class="step" @click="pax[p.key] = Math.max(p.key === 'adult' ? 1 : 0, pax[p.key] - 1)">−</text>
                <text class="strong">{{ pax[p.key] }}</text>
                <text class="step" @click="pax[p.key] = Math.min(9, pax[p.key] + 1)">+</text>
              </view>
            </view>
            <view class="muted small">价格是成人票；儿童、婴儿票价各平台规则不同，以平台页面为准。</view>
          </view>
        </view>
        <button class="btn sbtn" :disabled="anyLoading" @click="search"><Icon name="search" :size="16" color="#fff" /><text>{{ anyLoading ? "比价中…" : "查询机票" }}</text></button>
      </view>
    </view>

    <!-- 价格日历 + 走势 -->
    <view v-if="leg" class="cal-row">
      <view class="panel cal">
        <view class="cal-label">
          <view v-if="legs.length > 1" class="leg-tabs">
            <text v-for="l in legs" :key="l.key" class="leg-tab" :class="{ on: active === l.key }" @click="switchLeg(l.key)">{{ l.key === "out" ? "去程" : "返程" }}</text>
          </view>
          <text v-else class="strong">{{ leg.key === "out" ? "去程价格" : "返程价格" }}</text>
          <text class="muted small">（{{ leg.origin }} → {{ leg.destination }}）</text>
        </view>
        <view class="arrow" @click="shiftCalendar(-7)"><Icon name="back" :size="16" color="var(--ink)" /></view>
        <scroll-view scroll-x class="cal-days">
          <view class="cal-inner">
            <view v-for="d in calDays" :key="d.date" class="cal-day" :class="{ on: d.date === leg.date, low: d.price != null && d.price === calLow }" @click="pickDate(d.date)">
              <text class="cd-date">{{ Number(d.date.slice(5, 7)) }}月{{ Number(d.date.slice(8)) }}日</text>
              <text class="cd-price">{{ calLoading && d.price == null ? "…" : d.price != null ? "¥" + d.price : "—" }}</text>
            </view>
          </view>
        </scroll-view>
        <view class="arrow" @click="shiftCalendar(7)"><Icon name="right" :size="16" color="var(--ink)" /></view>
      </view>
      <view class="panel trend">
        <view class="trend-head"><text class="strong">价格走势</text><text v-if="calLow != null" class="muted small">这 7 天最低 <text class="low-t">¥{{ calLow }}</text></text></view>
        <svg v-if="trendPath" class="spark" viewBox="0 0 220 56" preserveAspectRatio="none">
          <path :d="trendPath.area" fill="var(--accent-soft)" />
          <path :d="trendPath.line" fill="none" stroke="var(--accent)" stroke-width="2" />
          <circle v-for="(p, i) in trendPath.points" :key="i" :cx="p[0]" :cy="p[1]" r="2.6" :fill="p[2] ? '#c2560c' : 'var(--accent)'" />
        </svg>
        <view class="muted small">飞猪按出发日期的每天最低价，不是历史走势</view>
      </view>
    </view>

    <!-- 筛选 -->
    <view v-if="leg?.result" class="panel filterbar">
      <text class="strong fb-label">筛选条件</text>
      <view v-for="f in FILTERS" :key="f.key" class="dd" :class="{ on: activeCount(f.key) }" @click.stop="openDd = openDd === f.key ? '' : f.key">
        <text>{{ f.label }}<text v-if="activeCount(f.key)">·{{ activeCount(f.key) }}</text></text>
        <Icon name="down" :size="12" color="var(--muted)" />
        <view v-if="openDd === f.key" class="pop" @click.stop>
          <template v-if="f.key === 'time'">
            <text v-for="s in SLOTS" :key="s.key" class="chip" :class="{ on: filter.slots.includes(s.key) }" @click="toggle(filter.slots, s.key)">{{ s.label }}</text>
          </template>
          <template v-else-if="f.key === 'airline'">
            <text v-for="a in airlines" :key="a" class="chip" :class="{ on: filter.airlines.includes(a) }" @click="toggle(filter.airlines, a)">{{ a }}</text>
          </template>
          <template v-else-if="f.key === 'stops'">
            <text v-for="s in STOPS" :key="s.key" class="chip" :class="{ on: filter.stops === s.key }" @click="filter.stops = s.key">{{ s.label }}</text>
          </template>
          <template v-else-if="f.key === 'hours'">
            <text v-for="h in HOURS" :key="h.label" class="chip" :class="{ on: filter.maxHours === h.value }" @click="filter.maxHours = h.value">{{ h.label }}</text>
          </template>
          <template v-else-if="f.key === 'airport'">
            <text v-for="a in airports" :key="a" class="chip" :class="{ on: filter.depAirports.includes(a) }" @click="toggle(filter.depAirports, a)">{{ a }}</text>
          </template>
          <template v-else-if="f.key === 'cabin'">
            <text v-for="c in CABINS" :key="c.key" class="chip" :class="{ on: cabin === c.key }" @click="setCabin(c.key)">{{ c.label }}</text>
            <view class="muted small pop-note">公务舱、头等舱只有飞猪能查</view>
          </template>
          <template v-else-if="f.key === 'price'">
            <text v-for="p in priceSteps" :key="p.label" class="chip" :class="{ on: filter.maxPrice === p.value }" @click="filter.maxPrice = p.value">{{ p.label }}</text>
          </template>
        </view>
      </view>
      <view class="check" @click="filter.stops = filter.stops === 'direct' ? 'all' : 'direct'"><view class="box" :class="{ on: filter.stops === 'direct' }" />仅看直飞</view>
      <text v-if="filterCount" class="link" @click="resetFilter">清除</text>
      <view class="dd sort-dd" @click.stop="openDd = openDd === 'sort' ? '' : 'sort'">
        <text>{{ SORTS.find((s) => s.key === sort)?.label }}</text><Icon name="down" :size="12" color="var(--muted)" />
        <view v-if="openDd === 'sort'" class="pop right" @click.stop>
          <text v-for="s in SORTS" :key="s.key" class="chip" :class="{ on: sort === s.key }" @click="pickSort(s.key)">{{ s.label }}</text>
        </view>
      </view>
    </view>

    <view v-if="leg?.loading" class="state">正在比较飞猪、携程、美团的价格，约 15–60 秒…</view>
    <view v-else-if="leg?.error" class="state err">{{ leg.error }}</view>
    <view v-else-if="!leg?.result" class="state muted">填好出发、到达和日期后点"查询机票"。</view>
    <view v-else class="grid">
      <!-- 左：航班列表 -->
      <view class="list">
        <view class="lhead">
          <text class="muted small">{{ shown.length }} 班<text v-if="shown.length !== leg.result.flights.length">（共 {{ leg.result.flights.length }} 班）</text> · {{ cabinLabel }} · {{ platformLine }}</text>
          <text class="muted small refresh-line">{{ ago(leg.result.queriedAt) }}查询 · <text class="link" @click="refreshLeg">{{ refreshing ? "刷新中…" : "刷新" }}</text></text>
        </view>
        <view
          v-for="(f, i) in shown"
          :key="f.flightNo"
          class="row"
          :class="{ on: selected?.flightNo === f.flightNo, top: i === 0 && topPick?.flight.flightNo === f.flightNo }"
          @click="select(f)"
        >
          <view v-if="i === 0 && topPick?.flight.flightNo === f.flightNo" class="r-badges">
            <text class="badge ai">智能推荐</text><text class="badge ok">{{ topPick.title }}</text>
          </view>
          <view class="r-main">
            <image v-if="logoOf(f) && !brokenLogo[logoOf(f)!]" class="logo img" :src="logoOf(f)!" mode="aspectFit" @error="brokenLogo[logoOf(f)!] = true" />
            <view v-else class="logo" :style="{ background: airlineColor(f.airline) }">{{ f.airline.slice(0, 1) }}</view>
            <view class="r-air">
              <text class="r-airline">{{ f.airline }} {{ f.flightNo }}</text>
              <text v-if="f.aircraft || isCurrent(f)" class="muted small">{{ f.aircraft || "" }}<text v-if="isCurrent(f)" class="cur-t">{{ f.aircraft ? " · " : "" }}行程里现在的航班</text></text>
            </view>
            <view class="r-end r-dep">
              <text class="t">{{ f.depTime }}</text>
              <text class="muted small">{{ f.depAirport }}{{ f.depCode ? " · " + f.depCode : "" }}</text>
            </view>
            <view class="r-mid">
              <text class="muted small">{{ durationText(minutesOf(f)) }}</text>
              <view class="line" />
              <text class="small" :class="f.transfer ? 'warn-t' : 'muted'">{{ f.transfer ? (f.via ? `经${f.via}中转` : "中转") : "直飞" }}</text>
            </view>
            <view class="r-end r-arr">
              <text class="t">{{ f.arrTime }}<text v-if="f.nextDay" class="nd">+1</text></text>
              <text class="muted small">{{ f.arrAirport }}{{ f.arrCode ? " · " + f.arrCode : "" }}</text>
            </view>
            <view class="r-price">
              <text class="price">{{ f.lowest != null ? "¥" + f.lowest.toLocaleString() : "—" }}</text>
              <text class="muted small">{{ f.lowestPlatform ? f.lowestPlatform + "最低" : "无价格" }}<text v-if="f.lowestPlatform === '途牛'">（可能有条件）</text></text>
            </view>
            <button class="btn small pick-btn" @click.stop="select(f)">选择</button>
          </view>
          <view v-if="i === 0 && topPick?.flight.flightNo === f.flightNo" class="r-reason"><Icon name="sparkle" :size="12" color="var(--accent)" /><text>推荐理由：{{ topPick.reasons.join("；") }}</text></view>
          <view v-else-if="fitOf(f) && fitOf(f)!.tone !== 'good'" class="r-reason warn"><text>{{ fitOf(f)!.text }}</text></view>
        </view>
        <view v-if="!shown.length" class="state muted">没有符合筛选的航班，放宽一下条件。</view>
        <view class="foot">价格是查询时各平台显示的{{ cabinLabel }}成人价，可能不含机建燃油，下单时以平台页面为准。平台没给餐食、行李额的结构化数据，所以没有"含行李""可退改"筛选；单个航班的行李和退改可以在右边查。预订链接会跳到平台，部分带推广参数，不影响排序和推荐；付款在平台上由你自己完成。</view>
      </view>

      <!-- 右：推荐、比价、退改（手机上点航班后是底部弹层） -->
      <view class="side" :class="{ sheet: !wide && detailOpen }" @click="detailOpen = false">
        <view class="side-inner" @click.stop>
          <view v-if="!wide && detailOpen" class="sheet-head"><text class="strong">{{ selected?.airline }} {{ selected?.flightNo }}</text><view class="d-x" @click="detailOpen = false"><Icon name="close" :size="16" color="var(--muted)" /></view></view>

          <view v-if="topPick && (wide || !detailOpen)" class="panel">
            <view class="ph"><text class="panel-title"><Icon name="sparkle" :size="15" color="var(--accent)" /> 智能航班推荐</text><text v-if="trip" class="tag-soft">基于你的行程</text></view>
            <view class="best" @click="select(topPick.flight)"><Icon name="star" :size="14" color="#c2560c" /><text>推荐航班：{{ topPick.flight.airline }} {{ topPick.flight.flightNo }}（¥{{ topPick.flight.lowest }}）</text></view>
            <view v-for="r in topReasons" :key="r.text" class="reason" :class="r.tone"><Icon :name="r.tone === 'bad' ? 'bell' : 'checkCircle'" :size="13" :color="r.tone === 'bad' ? 'var(--price)' : 'var(--ok)'" /><text>{{ r.text }}</text></view>
            <view v-for="p in picks.filter((x) => x !== topPick)" :key="p.key" class="alt" @click="select(p.flight)">
              <text class="muted small">{{ p.title }}：</text><text class="small strong">{{ p.flight.airline }} {{ p.flight.flightNo }} {{ p.flight.depTime }}</text><text class="small price-t"> ¥{{ p.flight.lowest }}</text>
            </view>
            <view class="muted small">按价格、起降时间、时长<text v-if="tripDay">和行程安排</text>算出来，不是 AI 写的。</view>
          </view>

          <template v-if="selected && (wide || detailOpen)">
            <view class="panel">
              <view class="ph"><text class="panel-title">同航班多平台价格（{{ selected.flightNo }} {{ leg.key === "out" ? "去程" : "返程" }}）</text></view>
              <view v-for="(_, k) in leg.result.platforms" :key="k" class="pf">
                <image v-if="PLATFORM_LOGOS[String(k)]" class="pf-logo img" :src="PLATFORM_LOGOS[String(k)]" mode="aspectFit" />
                <text v-else class="pf-logo" :style="{ background: platformColor(String(k)) }">{{ String(k).slice(0, 1) }}</text>
                <text class="pf-name">{{ k }}</text>
                <template v-if="selected.prices[k]?.price">
                  <text class="pf-price" :class="{ best: k === selected.lowestPlatform }">¥{{ selected.prices[k].price!.toLocaleString() }}</text>
                  <text class="muted small">{{ k === selected.lowestPlatform ? "最低" : `贵 ¥${selected.prices[k].price! - (selected.lowest || 0)}` }}<text v-if="selected.prices[k].tax"> · 另付税费 ¥{{ selected.prices[k].tax }}</text><text v-if="k === '途牛'"> · 可能是带条件的特惠价</text></text>
                  <text v-if="selected.prices[k].url" class="pf-go" @click="openUrl(selected.prices[k].url!)">去预订 ›</text>
                  <text v-else class="muted small">没给预订链接</text>
                </template>
                <text v-else class="muted small pf-none">{{ platformMissing(String(k)) }}</text>
              </view>
              <view v-if="selected.lowest != null" class="muted small">{{ pax.adult }} 位成人约 ¥{{ (selected.lowest * pax.adult).toLocaleString() }}<text v-if="pax.child || pax.infant">；儿童、婴儿票价以平台为准</text></view>
            </view>

            <view class="panel">
              <view class="ph"><text class="panel-title">退改与行李规则（{{ selected.flightNo }} {{ cabinLabel }}）</text></view>
              <template v-if="rulesOf(selected)?.data">
                <view class="rules-grid">
                  <view v-for="(v, k) in rulesOf(selected)!.data!.items" :key="k" class="rule"><text class="rule-k">{{ k }}</text><text>{{ v }}</text></view>
                </view>
                <view v-if="!Object.keys(rulesOf(selected)!.data!.items).length" class="small">{{ rulesOf(selected)!.data!.summary }}</view>
                <view class="muted small">来自携程问道的一般规则，具体费率随购票舱位变化，以购票页面为准。{{ ago(rulesOf(selected)!.data!.queriedAt) }}查询 · <text class="link" @click="loadRules(selected, true)">{{ rulesOf(selected)!.loading ? "重新查询中…" : "重新查" }}</text></view>
              </template>
              <view v-else-if="rulesOf(selected)?.error" class="err small">{{ rulesOf(selected)!.error }}</view>
              <button v-else class="btn ghost small" :disabled="rulesOf(selected)?.loading" @click="loadRules(selected)">{{ rulesOf(selected)?.loading ? "正在问携程，约 10–20 秒…" : "问携程：退改规则和行李额" }}</button>

              <template v-if="selected.prices['途牛'] || leg.result.platforms['途牛']">
                <view v-if="cabinsOf(selected)?.data" class="cabins">
                  <view class="muted small">途牛上这班的舱位（票面价从低到高，另付税费）：</view>
                  <view v-for="(c, i) in cabinsOf(selected)!.data!.slice(0, 6)" :key="i" class="cabin" :class="{ plain: !c.condition && !c.limits.length }">
                    <view class="cabin-top">
                      <text class="strong">¥{{ c.price }}</text><text v-if="c.tax" class="muted small"> +税 ¥{{ c.tax }}</text>
                      <text class="cabin-cond">{{ c.condition || "普通票" }}</text>
                    </view>
                    <view class="muted small">{{ [c.cabinClass, c.baggage, c.meal ? "餐食：" + c.meal : "", c.seats ? "余 " + c.seats + " 座" : ""].filter(Boolean).join(" · ") }}</view>
                    <view v-for="l in c.limits" :key="l" class="small warn-t">{{ l }}</view>
                  </view>
                  <view class="muted small">"普通票"是没有身份、人数限制的价格；带条件的（长者、新会员、青年等）要符合条件才能买。退改费率途牛没给细则，以下单页为准。 <text class="link" @click="loadCabins(selected, true)">{{ cabinsOf(selected)!.loading ? "重新查询中…" : "重新查（耗途牛额度）" }}</text></view>
                </view>
                <view v-else-if="cabinsOf(selected)?.error" class="err small">{{ cabinsOf(selected)!.error }}</view>
                <button v-else class="btn ghost small" :disabled="cabinsOf(selected)?.loading" @click="loadCabins(selected)">{{ cabinsOf(selected)?.loading ? "正在查途牛…" : "查途牛舱位：行李额、餐食、购买条件" }}</button>
              </template>
            </view>

            <view v-if="trip && leg.dayIndex != null" class="panel">
              <view class="ph"><text class="panel-title">写进行程</text></view>
              <view v-if="fitOf(selected)" class="reason" :class="fitOf(selected)!.tone === 'good' ? 'good' : 'bad'"><Icon :name="fitOf(selected)!.tone === 'good' ? 'checkCircle' : 'bell'" :size="13" :color="fitOf(selected)!.tone === 'good' ? 'var(--ok)' : 'var(--price)'" /><text>{{ fitOf(selected)!.text }}</text></view>
              <view class="muted small">写进第 {{ leg.dayIndex + 1 }} 天（{{ leg.key === "out" ? "去程" : "返程" }}），先看预览，确认后才保存；不会下单。</view>
              <button class="btn" :disabled="isCurrent(selected) || !!flightSheet" @click="openPreview(selected)">{{ isCurrent(selected) ? "已经是行程里的航班" : "写进行程（预览）" }}</button>
            </view>
          </template>
        </view>
      </view>
    </view>

    <!-- 写进行程预览 -->
    <view v-if="flightSheet" class="mask" @click="closeSheet">
      <view class="sheet-box" @click.stop>
        <view class="sh-head">
          <text class="sh-title">写进第 {{ flightSheet.day }} 天</text>
          <text class="preview-tag">预览，还没保存</text>
          <view class="d-x" @click="closeSheet"><Icon name="close" :size="16" color="var(--muted)" /></view>
        </view>
        <view v-if="flightSheet.error" class="err">{{ flightSheet.error }}</view>
        <view v-else-if="!flightSheet.preview" class="muted small">正在核对行程…</view>
        <template v-else>
          <view class="swap-box">
            <view v-if="flightSheet.preview.replacesName" class="old">{{ flightSheet.preview.replacesName }}<text class="muted small"> · {{ flightSheet.preview.replacesTime }}</text></view>
            <view class="new">{{ flightSheet.preview.item.name }}<text class="muted small"> · {{ flightSheet.preview.item.start }}–{{ flightSheet.preview.item.end }}</text></view>
            <view v-if="flightSheet.preview.item.cost" class="muted small">票价 ¥{{ flightSheet.preview.item.cost }} 记进行程花费</view>
          </view>
          <view class="checks">
            <view v-if="!flightSheet.preview.conflicts.length" class="reason good"><Icon name="checkCircle" :size="13" color="var(--ok)" /><text>和这天的其他安排时间不冲突</text></view>
            <view v-for="c in flightSheet.preview.conflicts" :key="c.id" class="reason bad"><Icon name="bell" :size="13" color="var(--price)" /><text>「{{ c.name }}」{{ c.problem }}<text v-if="c.fixed" class="strong">（这一项你锁定或已预约了）</text></text></view>
            <view v-for="n in flightSheet.preview.notes" :key="n" class="reason note"><Icon name="bell" :size="13" color="var(--muted)" /><text>{{ n }}</text></view>
          </view>
          <view class="acts">
            <button class="btn" :disabled="saving" @click="confirmSave">{{ saving ? "保存中…" : "确认写进行程" }}</button>
            <button class="btn ghost" :disabled="saving" @click="closeSheet">不用了</button>
          </view>
        </template>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import Icon from "./Icon.vue";
import { api } from "../api";
import { showError } from "../api/http";
import type { Flight, FlightPick, FlightPreview, FlightRules, FlightSearch, ItineraryDay, TripDetail, TuniuCabin } from "../api/types";
import { addDays, ago, openUrl, ymd } from "../utils/format";
import { cityOf, currentCity } from "../utils/place";
import { useWide } from "../utils/screen";
import { SLOTS, airportName, applyFilter, durationText, minutesOf, recommend, tripFit, warnings, type FlightFilter, type FlightSort } from "../utils/flights";

/**
 * 机票智能比价：单程或往返，三个平台按航班号合并；上面是前后 7 天每天的最低价（飞猪）和走势，筛选做成下拉，
 * 左边航班列表、右边推荐理由、同航班多平台价格、退改行李（携程问道按需查）和写进行程。
 * 带 trip 参数时日期取行程首尾两天、出发城市取行程里记的，推荐看能不能衔接上当天的安排；
 * 什么都没带时，如果有即将出发的行程，顶部问一句要不要按它填。只跳到平台预订，不下单、不付款。
 * query：origin、destination、date、back、trip
 */
const props = defineProps<{ query: Record<string, string | undefined> }>();

const MODES = [
  { key: "one", label: "单程" },
  { key: "round", label: "往返" },
] as const;
const PAX = [
  { key: "adult", label: "成人", hint: "12 岁以上" },
  { key: "child", label: "儿童", hint: "2–12 岁" },
  { key: "infant", label: "婴儿", hint: "2 岁以下" },
] as const;
const FILTERS = [
  { key: "time", label: "起飞时间" },
  { key: "airline", label: "航空公司" },
  { key: "stops", label: "直飞/中转" },
  { key: "hours", label: "飞行时长" },
  { key: "airport", label: "机场" },
  { key: "cabin", label: "舱位等级" },
  { key: "price", label: "价格" },
];
const STOPS: { key: FlightFilter["stops"]; label: string }[] = [
  { key: "all", label: "不限" },
  { key: "direct", label: "直飞" },
  { key: "transfer", label: "中转" },
];
const HOURS = [
  { label: "不限", value: null as number | null },
  { label: "3 小时内", value: 3 },
  { label: "5 小时内", value: 5 },
  { label: "8 小时内", value: 8 },
];
const CABINS = [
  { key: "ECONOMY", label: "经济舱" },
  { key: "BUSINESS", label: "公务舱" },
  { key: "FIRST", label: "头等舱" },
];
const SORTS: { key: FlightSort; label: string }[] = [
  { key: "recommend", label: "推荐排序" },
  { key: "price", label: "价格最低" },
  { key: "dep", label: "起飞最早" },
  { key: "duration", label: "耗时最短" },
];
/** 有图标的航司（按航班号前两位，static/logos/airlines 下的图片）和平台 */
const AIRLINE_LOGOS = new Set(["CZ", "MU", "CA", "HU", "9C", "HO", "ZH", "3U", "MF", "SC", "Y8", "JD", "GS", "8L", "PN", "KN", "G5", "EU", "TV", "BK", "FM", "QW", "NS", "GJ", "9H", "GX", "DZ", "RY", "A6", "DR", "UQ", "JR", "GT", "LT"]);
const PLATFORM_LOGOS: Record<string, string> = { 携程: "/static/logos/platforms/ctrip.png", 美团: "/static/logos/platforms/meituan.png" };
const AIRLINE_COLORS = ["#d62828", "#1d4ed8", "#0f766e", "#b45309", "#7c3aed", "#be185d", "#0369a1", "#15803d"];
const NO_FILTER: FlightFilter = { stops: "all", slots: [], airlines: [], depAirports: [], maxPrice: null, maxHours: null };

interface Leg {
  key: "out" | "back";
  origin: string;
  destination: string;
  date: string;
  dayIndex: number | null;
  loading: boolean;
  error: string;
  result: FlightSearch | null;
}

const wide = useWide();
const today = ymd(new Date());
const form = reactive({ origin: "", destination: currentCity(), date: ymd(new Date(Date.now() + 7 * 86400000)), back: "" });
const mode = ref<"one" | "round">("one");
const pax = reactive({ adult: 1, child: 0, infant: 0 });
const paxOpen = ref(false);
const cabin = ref("ECONOMY");
const trip = ref<{ id: number; title: string; days: ItineraryDay[] } | null>(null);
const legs = ref<Leg[]>([]);
const active = ref<"out" | "back">("out");
const filter = reactive<FlightFilter>({ ...NO_FILTER, slots: [], airlines: [], depAirports: [] });
const sort = ref<FlightSort>("recommend");
const openDd = ref("");
const selected = ref<Flight | null>(null);
const detailOpen = ref(false);
const rules = reactive<Record<string, { loading: boolean; data: FlightRules | null; error: string }>>({});
const cabins = reactive<Record<string, { loading: boolean; data: TuniuCabin[] | null; error: string }>>({});
const flightSheet = ref<{ day: number; pick: FlightPick; preview: FlightPreview | null; error: string } | null>(null);
const saving = ref(false);
/** 价格日历：中心日期和每天最低价 */
const calCenter = ref("");
const calPrices = reactive<Record<string, number | null>>({});
const calLoading = ref(false);
/** 正在刷新当前这一程（旧结果先留着） */
const refreshing = ref(false);
/** 预览、日历的请求序号：条件变了时丢掉旧的结果 */
let previewSeq = 0;
let calSeq = 0;

const leg = computed(() => legs.value.find((l) => l.key === active.value));
const anyLoading = computed(() => legs.value.some((l) => l.loading));
const tripDay = computed(() => (trip.value && leg.value?.dayIndex != null ? trip.value.days[leg.value.dayIndex] : undefined));
const filtered = computed(() => applyFilter(leg.value?.result?.flights || [], filter, sort.value === "recommend" ? "price" : sort.value));
const picks = computed(() => recommend(filtered.value, tripDay.value, active.value));
/** 推荐排序：推荐的那班放第一，其余按价格、直飞、时长、起飞时间和行程衔接打分 */
const shown = computed(() => {
  if (sort.value !== "recommend") return filtered.value;
  const top = picks.value.find((p) => p.key === "fit") || picks.value[0];
  const penalty = (f: Flight) => ({ bad: 600, tight: 150, good: 0 })[fitOf(f)?.tone || "good"];
  const rest = applyFilter(filtered.value, NO_FILTER, "recommend");
  const order = new Map(rest.map((f, i) => [f.flightNo, i]));
  const score = (f: Flight) => order.get(f.flightNo)! * 20 + penalty(f);
  return [...filtered.value].sort((a, b) => (a === top?.flight ? -1 : b === top?.flight ? 1 : score(a) - score(b)));
});
const topPick = computed(() => picks.value.find((p) => p.key === "fit") || picks.value[0] || null);
const topReasons = computed(() => (topPick.value ? reasonsOf(topPick.value.flight) : []));
const airlines = computed(() => [...new Set((leg.value?.result?.flights || []).map((f) => f.airline))]);
const airports = computed(() => [...new Set((leg.value?.result?.flights || []).map((f) => airportName(f.depAirport)))].filter(Boolean));
const filterCount = computed(() => filter.slots.length + filter.airlines.length + filter.depAirports.length + (filter.stops !== "all" ? 1 : 0) + (filter.maxPrice != null ? 1 : 0) + (filter.maxHours != null ? 1 : 0));
const cabinLabel = computed(() => CABINS.find((c) => c.key === cabin.value)?.label || "经济舱");
const priceSteps = computed(() => {
  const low = Math.min(...(leg.value?.result?.flights || []).map((f) => f.lowest ?? Infinity));
  if (!isFinite(low)) return [{ label: "不限", value: null as number | null }];
  const steps = [1.2, 1.5, 2].map((k) => Math.ceil((low * k) / 50) * 50);
  return [{ label: "不限", value: null as number | null }, ...[...new Set(steps)].map((v) => ({ label: `¥${v} 内`, value: v as number | null }))];
});
const platformLine = computed(() =>
  Object.entries(leg.value?.result?.platforms || {})
    .map(([k, v]) => `${k}${String(v).startsWith("OK") ? " " + (String(v).match(/\d+/)?.[0] || "") + " 班" : "没查到"}`)
    .join("、"),
);
/** 机场三字码（取查到的第一班） */
const codes = computed(() => {
  const f = leg.value?.result?.flights.find((x) => x.depCode);
  if (!f) return { origin: "", destination: "" };
  const out = leg.value?.key === "out";
  return { origin: out ? f.depCode! : f.arrCode || "", destination: out ? f.arrCode || "" : f.depCode! };
});
const calDays = computed(() => {
  if (!calCenter.value) return [];
  return [-3, -2, -1, 0, 1, 2, 3].map((k) => addDays(calCenter.value, k)).filter((d) => d >= today).map((d) => ({ date: d, price: calPrices[d] ?? null }));
});
const calLow = computed(() => {
  const ps = calDays.value.map((d) => d.price).filter((p): p is number => p != null);
  return ps.length ? Math.min(...ps) : null;
});
/** 走势小图：按 7 天价格画折线，最低点标橙色 */
const trendPath = computed(() => {
  const ds = calDays.value.filter((d) => d.price != null);
  if (ds.length < 2) return null;
  const ps = ds.map((d) => d.price!);
  const min = Math.min(...ps);
  const max = Math.max(...ps);
  const pts = ds.map((d, i) => [6 + (i * 208) / (ds.length - 1), max === min ? 28 : 8 + ((max - d.price!) / (max - min)) * 40, d.price === min] as [number, number, boolean]);
  const line = pts.map((p, i) => `${i ? "L" : "M"}${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" ");
  return { line, area: `${line} L${pts[pts.length - 1][0].toFixed(1)},56 L${pts[0][0].toFixed(1)},56 Z`, points: pts };
});
/** 行程这天现在的航班号 */
const currentFlightNo = computed(() => {
  const item = tripDay.value?.items.find((i) => i.type === "TRANSPORT" && /[A-Z0-9]{2}\d{3,4}/.test(i.name));
  return item?.name.match(/[A-Z0-9]{2}\d{3,4}/)?.[0] || null;
});

/** 点页面别处收起下拉和乘客面板 */
function closePops() {
  openDd.value = "";
  paxOpen.value = false;
}
onMounted(() => {
  // #ifdef H5
  document.addEventListener("click", closePops);
  // #endif
});
onBeforeUnmount(() => {
  // #ifdef H5
  document.removeEventListener("click", closePops);
  // #endif
});

init();

/** 上次查机票用的出发城市，下次打开预先填上（大多数人总从同一个城市出发） */
const LAST_ORIGIN = "flight_last_origin";

async function init() {
  const q = props.query;
  form.origin = uni.getStorageSync(LAST_ORIGIN) || "";
  if (q.origin) form.origin = decodeURIComponent(q.origin);
  if (q.destination) form.destination = decodeURIComponent(q.destination);
  if (q.date) form.date = q.date;
  if (q.back) {
    form.back = q.back;
    mode.value = "round";
  }
  if (q.trip) await linkTrip(Number(q.trip));
  if (form.origin && form.destination && (q.trip || (q.origin && q.destination && q.date))) search();
  else if (!q.trip && !q.destination && !q.date) findUpcomingTrip();
}

/** 顶部建议用的行程：最近一个还没出发、有日期、而且要坐飞机的行程（本地游、目的地就是出发城市的不算） */
const suggestTrip = ref<{ id: number; title: string; when: string } | null>(null);

async function findUpcomingTrip() {
  try {
    const today = ymd(new Date());
    const next = (await api.trips())
      .filter((t) => t.status !== "FINISHED" && t.startDate && t.startDate >= today
        && !!cityOf(t.destination) && cityOf(t.destination) !== (t.origin || currentCity()))
      .sort((a, b) => a.startDate!.localeCompare(b.startDate!))[0];
    if (next) suggestTrip.value = { id: next.id, title: next.title, when: `${Number(next.startDate!.slice(5, 7))}月${Number(next.startDate!.slice(8, 10))}日出发` };
  } catch {
    // 拿不到行程就不提示，不影响手动查
  }
}

async function useSuggested() {
  const t = suggestTrip.value;
  suggestTrip.value = null;
  if (!t) return;
  await linkTrip(t.id);
  if (form.origin && form.destination) search();
}

/** 关联行程：日期取首尾两天（往返），到达城市取目的地；出发城市优先用行程里记的，没有再从行程里的航班猜，还没有就用当前城市 */
async function linkTrip(id: number) {
  try {
    const detail: TripDetail = await api.trip(id);
    const days = detail.version.itinerary?.days || [];
    if (!days.length) return;
    trip.value = { id, title: detail.trip.title, days };
    form.date = days[0].date;
    form.back = days.length > 1 ? days[days.length - 1].date : "";
    mode.value = form.back ? "round" : "one";
    form.destination = cityOf(detail.trip.destination) || form.destination;
    const flight = days[0].items.find((i) => i.type === "TRANSPORT" && /[A-Z0-9]{2}\d{3,4}/.test(i.name) && i.name.includes("→"));
    const from = flight?.name.split("→")[0].replace(/[A-Z0-9]{2}\d{3,4}/g, "").replace(/[A-Za-z0-9\s]/g, "").trim();
    // 出发城市：行程里记的 → 行程里航班的出发地 → 当前城市 → 上次查的；和目的地一样的跳过（本地游就空着让用户填，也不自动搜）
    // 还没选航班的（"航班待定"）说明里常写着"深圳直飞北京"，从这句里取出发城市
    const pending = days[0].items.find((i) => i.type === "TRANSPORT" && /(直飞|飞往)/.test(`${i.name} ${i.note || ""}`));
    const said = `${pending?.name || ""} ${pending?.note || ""}`.match(/([\u4e00-\u9fa5]{2,4}?)(?:直飞|飞往)/)?.[1];
    const candidates = [detail.trip.origin, from?.slice(0, 2), said, currentCity(), form.origin];
    form.origin = candidates.find((c) => !!c && c !== form.destination) || "";
    // 行程填了人数就按人数算成人票（儿童、婴儿让用户自己调）
    if (detail.trip.people && detail.trip.people <= 9) pax.adult = detail.trip.people;
  } catch (e) {
    showError(e);
  }
}

function unlink() {
  trip.value = null;
  legs.value.forEach((l) => (l.dayIndex = null));
}

function setMode(m: "one" | "round") {
  mode.value = m;
  if (m === "round" && !form.back) form.back = addDays(form.date, 3);
}

function setDate(v: string) {
  form.date = v;
  if (form.back && form.back < v) form.back = v;
}

function swapCities() {
  [form.origin, form.destination] = [form.destination, form.origin];
}

function cnDay(d: string) {
  const w = "日一二三四五六"[new Date(d + "T00:00:00").getDay()];
  return `${Number(d.slice(5, 7))}月${Number(d.slice(8))}日（周${w}）`;
}

function dayOf(date: string) {
  const i = (trip.value?.days || []).findIndex((d) => d.date === date);
  return i >= 0 ? i : null;
}

/** 去程、返程同时查 */
function search() {
  const from = form.origin.trim();
  const to = form.destination.trim();
  if (!from || !to) {
    uni.showToast({ title: "填一下出发和到达城市", icon: "none" });
    return;
  }
  uni.setStorageSync(LAST_ORIGIN, from);
  legs.value = [{ key: "out", origin: from, destination: to, date: form.date, dayIndex: dayOf(form.date), loading: true, error: "", result: null }];
  if (mode.value === "round" && form.back) legs.value.push({ key: "back", origin: to, destination: from, date: form.back, dayIndex: dayOf(form.back), loading: true, error: "", result: null });
  active.value = "out";
  selected.value = null;
  resetFilter();
  clearCalendar();
  calCenter.value = legs.value[0].date;
  // 价格日历等航班查完再查，免得一起挤飞猪被限流
  Promise.all(legs.value.map((l) => runLeg(l))).then(() => loadCalendar(legs.value[0]?.date || form.date));
}

async function runLeg(l: Leg, refresh = false) {
  const target = () => legs.value.find((x) => x.key === l.key && x.date === l.date);
  try {
    const data = await api.searchFlights(l.origin, l.destination, l.date, cabin.value === "ECONOMY" ? undefined : cabin.value, refresh);
    const t = target();
    if (!t) return;
    if (data.error) t.error = data.error;
    else if (!data.flights.length) t.error = data.notice || "这天各平台都没查到航班，换个日期试试";
    else {
      t.error = "";
      t.result = data;
    }
    if (t.key === active.value && t.result && wide.value) {
      // 刷新时保留原来选中的那班（还在的话）
      const keep = refresh && selected.value ? t.result.flights.find((f) => f.flightNo === selected.value!.flightNo) : null;
      selected.value = keep || topPick.value?.flight || null;
    }
  } catch (e) {
    const t = target();
    // 刷新失败时留着旧结果，只弹提示
    if (refresh && t?.result) showError(e);
    else if (t) t.error = e instanceof Error ? e.message : String(e);
  } finally {
    const t = target();
    if (t) t.loading = false;
  }
}

function clearCalendar() {
  Object.keys(calPrices).forEach((k) => delete calPrices[k]);
}

/** 刷新这一程：跳过缓存重新比价，旧结果先留着不清空；价格日历也重新查 */
async function refreshLeg() {
  const l = leg.value;
  if (!l || refreshing.value || l.loading) return;
  refreshing.value = true;
  try {
    await runLeg(l, true);
    await loadCalendar(calCenter.value || l.date, true);
  } finally {
    refreshing.value = false;
  }
}

/** 价格日历：以 center 为中心的 7 天，只查飞猪 */
async function loadCalendar(center: string, refresh = false) {
  const l = leg.value;
  if (!l) return;
  const mine = ++calSeq;
  calCenter.value = center;
  calLoading.value = true;
  try {
    const list = await api.flightCalendar(l.origin, l.destination, center, refresh);
    if (mine !== calSeq) return;
    list.forEach((d) => (calPrices[d.date] = d.price));
  } catch {
    // 日历是辅助信息，查不到就显示"—"
  } finally {
    if (mine === calSeq) calLoading.value = false;
  }
}

/** 日历往前、往后翻一周；最早到今天后 3 天为中心 */
function shiftCalendar(days: number) {
  const earliest = addDays(today, 3);
  let next = addDays(calCenter.value || form.date, days);
  if (next < earliest) next = earliest;
  if (next === calCenter.value) return;
  clearCalendar();
  loadCalendar(next);
}

/** 点日历里的某天：这一程改成那天重新查 */
function pickDate(date: string) {
  const l = leg.value;
  if (!l || l.date === date || l.loading) return;
  if (l.key === "out") form.date = date;
  else form.back = date;
  l.date = date;
  l.dayIndex = dayOf(date);
  l.loading = true;
  l.error = "";
  l.result = null;
  selected.value = null;
  runLeg(l);
}

function switchLeg(k: "out" | "back") {
  if (active.value === k) return;
  active.value = k;
  resetFilter();
  selected.value = wide.value ? topPick.value?.flight || null : null;
  clearCalendar();
  if (leg.value) loadCalendar(leg.value.date);
}

function setCabin(c: string) {
  openDd.value = "";
  if (cabin.value === c) return;
  cabin.value = c;
  if (legs.value.length) search();
}

function pickSort(s: FlightSort) {
  sort.value = s;
  openDd.value = "";
}

function resetFilter() {
  Object.assign(filter, { ...NO_FILTER, slots: [], airlines: [], depAirports: [] });
}

function activeCount(key: string) {
  switch (key) {
    case "time":
      return filter.slots.length;
    case "airline":
      return filter.airlines.length;
    case "stops":
      return filter.stops !== "all" ? 1 : 0;
    case "hours":
      return filter.maxHours != null ? 1 : 0;
    case "airport":
      return filter.depAirports.length;
    case "cabin":
      return cabin.value !== "ECONOMY" ? 1 : 0;
    case "price":
      return filter.maxPrice != null ? 1 : 0;
    default:
      return 0;
  }
}

function toggle(list: string[], v: string) {
  const i = list.indexOf(v);
  i >= 0 ? list.splice(i, 1) : list.push(v);
}

function select(f: Flight) {
  selected.value = f;
  if (!wide.value) detailOpen.value = true;
}

function isCurrent(f: Flight) {
  return !!currentFlightNo.value && f.flightNo.split("/")[0] === currentFlightNo.value;
}

function fitOf(f: Flight) {
  return tripFit(f, tripDay.value, active.value);
}

const brokenLogo = reactive<Record<string, boolean>>({});

/** 航司图标：取航班号前两位（中转取第一段），没有图标返回空 */
function logoOf(f: Flight) {
  const code = f.flightNo.slice(0, 2).toUpperCase();
  return AIRLINE_LOGOS.has(code) ? `/static/logos/airlines/${code}.png` : null;
}

/** 航司小标：没有图标时按名字固定一个颜色 */
function airlineColor(name: string) {
  let h = 0;
  for (const c of name) h = (h * 31 + c.charCodeAt(0)) % 997;
  return AIRLINE_COLORS[h % AIRLINE_COLORS.length];
}

function platformColor(name: string) {
  return ({ 飞猪: "#ff6a00", 携程: "#2577e3", 美团: "#f5b400", 途牛: "#16a34a" } as Record<string, string>)[name] || "#64748b";
}

/** 某平台没查到这班的原因 */
function platformMissing(name: string) {
  const status = String(leg.value?.result?.platforms[name] || "");
  return status.startsWith("OK") ? "这个平台没查到这班" : status.replace(/^FAILED：?/, "").replace(/^EMPTY.*/, "这天没查到") || "没查到";
}

/** 推荐理由：好的地方和要注意的地方，全部按数据算 */
function reasonsOf(f: Flight) {
  const out: { tone: "good" | "bad"; text: string }[] = [];
  const all = (leg.value?.result?.flights || []).filter((x) => x.lowest != null);
  const cheapest = Math.min(...all.map((x) => x.lowest!));
  const fit = fitOf(f);
  out.push({ tone: "good", text: `时间：${f.depTime} 出发，${f.arrTime}${f.nextDay ? "（次日）" : ""} 到达${fit?.tone === "good" ? "，" + fit.text : ""}` });
  if (f.lowest != null) out.push({ tone: "good", text: f.lowest === cheapest ? `价格：这天 ${all.length} 班里最便宜（¥${f.lowest}）` : `价格：¥${f.lowest}，比最便宜的贵 ¥${f.lowest - cheapest}` });
  const priced = Object.values(f.prices).map((p) => p.price).filter((p): p is number => p != null);
  if (priced.length > 1) out.push({ tone: "good", text: `比价：${priced.length} 个平台都有，${f.lowestPlatform}最低，最多差 ¥${Math.max(...priced) - Math.min(...priced)}` });
  if (!f.transfer) out.push({ tone: "good", text: `直飞，全程 ${durationText(minutesOf(f))}` });
  // 往返时顺带说返程推荐
  const back = active.value === "out" ? legs.value.find((l) => l.key === "back") : undefined;
  if (back?.result) {
    const bp = recommend(applyFilter(back.result.flights, NO_FILTER, "recommend"), back.dayIndex != null ? trip.value?.days[back.dayIndex] : undefined, "back");
    const b = bp.find((p) => p.key === "fit") || bp[0];
    if (b) out.push({ tone: "good", text: `返程：${Number(back.date.slice(5, 7))}月${Number(back.date.slice(8))}日 ${b.flight.flightNo} ${b.flight.depTime} 起飞，¥${b.flight.lowest}` });
  }
  warnings(f, tripDay.value, active.value).forEach((w) => out.push({ tone: "bad", text: w }));
  return out;
}

function rulesOf(f: Flight) {
  return rules[f.flightNo + "|" + leg.value?.date];
}

async function loadRules(f: Flight, refresh = false) {
  const l = leg.value;
  if (!l) return;
  const key = f.flightNo + "|" + l.date;
  // 重新查时先留着旧结果
  if (refresh && rules[key]?.data) rules[key].loading = true;
  else rules[key] = { loading: true, data: null, error: "" };
  try {
    rules[key].data = await api.flightRules(f.flightNo, l.origin, l.destination, l.date, refresh);
    rules[key].error = "";
  } catch (e) {
    rules[key].error = e instanceof Error ? e.message : String(e);
  } finally {
    rules[key].loading = false;
  }
}

function cabinsOf(f: Flight) {
  return cabins[f.flightNo + "|" + leg.value?.date];
}

/** 途牛舱位：每天只有 50 次额度，点了才查 */
async function loadCabins(f: Flight, refresh = false) {
  const l = leg.value;
  if (!l) return;
  const key = f.flightNo + "|" + l.date;
  if (refresh && cabins[key]?.data) cabins[key].loading = true;
  else cabins[key] = { loading: true, data: null, error: "" };
  try {
    cabins[key].data = await api.tuniuCabins(l.origin, l.destination, l.date, f.flightNo.split("/")[0], refresh);
    cabins[key].error = "";
  } catch (e) {
    cabins[key].error = e instanceof Error ? e.message : String(e);
  } finally {
    cabins[key].loading = false;
  }
}

function pickOf(f: Flight): FlightPick {
  const platform = f.lowestPlatform;
  return {
    flightNo: f.flightNo,
    airline: f.airline,
    depTime: f.depTime,
    arrTime: f.arrTime,
    depAirport: f.depAirport,
    arrAirport: f.arrAirport,
    price: f.lowest,
    platform,
    url: platform ? f.prices[platform]?.url || null : null,
  };
}

async function openPreview(f: Flight) {
  const t = trip.value;
  const l = leg.value;
  if (!t || l?.dayIndex == null || isCurrent(f)) return;
  const pick = pickOf(f);
  const seq = ++previewSeq;
  flightSheet.value = { day: l.dayIndex + 1, pick, preview: null, error: "" };
  try {
    const p = await api.previewFlight(t.id, l.dayIndex + 1, pick);
    if (seq === previewSeq && flightSheet.value) flightSheet.value.preview = p;
  } catch (e) {
    if (seq === previewSeq && flightSheet.value) flightSheet.value.error = e instanceof Error ? e.message : String(e);
  }
}

function closeSheet() {
  if (saving.value) return;
  previewSeq++;
  flightSheet.value = null;
}

async function confirmSave() {
  const s = flightSheet.value;
  const t = trip.value;
  if (!s?.preview || !t) return;
  saving.value = true;
  try {
    const detail = await api.saveFlight(t.id, s.day, s.pick, s.preview.replacesId);
    t.days = detail.version.itinerary?.days || t.days;
    flightSheet.value = null;
    const to = detail.version.version;
    uni.showModal({
      title: `已写进行程，存成第 ${to} 版`,
      content: `第 ${s.day} 天：${s.pick.flightNo} ${s.pick.depTime}–${s.pick.arrTime}。还没有下单，请到平台预订付款，订好后在行程里标记"已预约"。`,
      confirmText: "看前后对比",
      cancelText: "留在这里",
      success: (r) => {
        if (r.confirm) uni.navigateTo({ url: `/pages/plan/updated?id=${t.id}&from=${to - 1}&to=${to}` });
      },
    });
  } catch (e) {
    showError(e);
    flightSheet.value = null;
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.fb { display: flex; flex-direction: column; gap: 12px; }
.panel { background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 14px; display: flex; flex-direction: column; gap: 10px; }
.panel-title { font-weight: 800; font-size: 15px; display: flex; align-items: center; gap: 4px; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.strong { font-weight: 700; }
.link { color: var(--accent); cursor: pointer; font-size: 12px; white-space: nowrap; }
.err { color: var(--price); font-size: 13px; line-height: 1.5; }
.state { text-align: center; font-size: 13px; padding: 28px 0; }
.state.err { color: var(--price); }
.warn-t { color: var(--price); }
.price-t { color: var(--price); font-weight: 700; }
.cur-t { color: var(--accent); font-weight: 700; }

.hero { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; padding: 18px 20px; border-radius: 16px; background: linear-gradient(110deg, var(--accent-soft), var(--surface) 70%); border: 1px solid var(--line); }
.hero-title { font-size: 22px; font-weight: 800; }
.hero-sub { font-size: 13px; color: var(--muted); margin-top: 4px; line-height: 1.5; }
.unlink { font-size: 12px; color: var(--muted); cursor: pointer; white-space: nowrap; }
.suggest { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 12px 16px; border-radius: 12px; background: var(--accent-soft); font-size: 14px; }
.suggest-text { flex: 1; min-width: 200px; }
.suggest-acts { display: flex; align-items: center; gap: 14px; }
.suggest-btn { padding: 5px 14px; border-radius: 999px; background: var(--accent); color: #fff; font-weight: 600; cursor: pointer; white-space: nowrap; }
.suggest-no { color: var(--muted); cursor: pointer; white-space: nowrap; }

.modes { display: flex; gap: 20px; }
.mode { display: flex; align-items: center; gap: 6px; font-size: 14px; cursor: pointer; }
.radio { width: 14px; height: 14px; border-radius: 50%; border: 2px solid var(--line); box-sizing: border-box; }
.mode.on .radio { border: 4px solid var(--accent); }
.sbar { display: flex; flex-wrap: wrap; gap: 8px; align-items: stretch; }
.sfield { position: relative; display: flex; flex-direction: column; gap: 2px; padding: 8px 12px; border-radius: 10px; background: var(--surface-2); min-width: 120px; cursor: pointer; box-sizing: border-box; }
.city-f { flex: 1; min-width: 130px; }
.city-row { display: flex; align-items: baseline; gap: 6px; }
.sk { font-size: 11px; color: var(--muted); }
.sv { font-size: 16px; font-weight: 700; white-space: nowrap; }
.city-row .sv { flex: 1; min-width: 0; }
.code { font-size: 12px; color: var(--muted); font-weight: 700; }
.swap { width: 34px; height: 34px; align-self: center; border-radius: 50%; border: 1px solid var(--line); background: var(--surface); display: flex; align-items: center; justify-content: center; cursor: pointer; flex-shrink: 0; }
.sbtn { margin: 0; gap: 6px; padding: 0 26px; }
.pop { position: absolute; top: calc(100% + 6px); left: 0; z-index: 30; min-width: 220px; max-width: 360px; background: var(--surface); border: 1px solid var(--line); border-radius: 12px; box-shadow: 0 10px 30px rgba(15, 23, 42, 0.15); padding: 10px; display: flex; flex-wrap: wrap; gap: 6px; cursor: default; font-weight: 400; color: var(--ink); }
.pop.right { left: auto; right: 0; }
.pax-pop { flex-direction: column; flex-wrap: nowrap; gap: 10px; min-width: 260px; }
.pax-row { display: flex; justify-content: space-between; align-items: center; font-size: 13px; }
.stepper { display: flex; align-items: center; gap: 10px; }
.step { width: 24px; height: 24px; border-radius: 6px; border: 1px solid var(--line); display: inline-flex; align-items: center; justify-content: center; cursor: pointer; }
.pop-note { width: 100%; }

.cal-row { display: flex; flex-direction: column; gap: 12px; }
.cal { flex-direction: row; align-items: center; gap: 8px; padding: 10px 12px; }
.cal-label { display: flex; flex-direction: column; gap: 4px; min-width: 96px; flex-shrink: 0; }
.leg-tabs { display: flex; background: var(--surface-2); border-radius: 8px; padding: 2px; align-self: flex-start; }
.leg-tab { font-size: 12px; padding: 3px 10px; border-radius: 6px; color: var(--muted); cursor: pointer; }
.leg-tab.on { background: var(--surface); color: var(--accent); font-weight: 700; }
.arrow { width: 28px; height: 52px; border-radius: 8px; border: 1px solid var(--line); display: flex; align-items: center; justify-content: center; cursor: pointer; flex-shrink: 0; }
.cal-days { flex: 1; min-width: 0; white-space: nowrap; }
.cal-inner { display: inline-flex; gap: 6px; }
.cal-day { display: inline-flex; flex-direction: column; align-items: center; gap: 2px; min-width: 84px; padding: 7px 6px; border-radius: 10px; border: 1px solid var(--line); cursor: pointer; }
.cal-day.on { background: var(--accent); border-color: var(--accent); color: #fff; }
.cd-date { font-size: 12px; }
.cd-price { font-size: 15px; font-weight: 800; font-variant-numeric: tabular-nums; }
.cal-day.low:not(.on) .cd-price { color: #c2560c; }
.trend { gap: 4px; padding: 10px 14px; }
.trend-head { display: flex; justify-content: space-between; align-items: baseline; gap: 8px; }
.low-t { color: #c2560c; font-weight: 800; }
.spark { width: 100%; height: 56px; }

.filterbar { flex-direction: row; flex-wrap: wrap; align-items: center; gap: 8px; padding: 10px 14px; }
.fb-label { margin-right: 4px; }
.dd { position: relative; display: inline-flex; align-items: center; gap: 4px; font-size: 13px; padding: 6px 12px; border-radius: 8px; border: 1px solid var(--line); cursor: pointer; white-space: nowrap; }
.dd.on { border-color: var(--accent); color: var(--accent); font-weight: 600; }
.sort-dd { margin-left: auto; }
.check { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; cursor: pointer; }
.box { width: 14px; height: 14px; border-radius: 4px; border: 2px solid var(--line); box-sizing: border-box; }
.box.on { background: var(--accent); border-color: var(--accent); }
.chip { font-size: 12px; padding: 5px 11px; border-radius: 999px; background: var(--surface-2); cursor: pointer; white-space: nowrap; }
.chip.on { background: var(--accent); color: #fff; font-weight: 600; }

.grid { display: flex; flex-direction: column; gap: 12px; }
.list { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.row { display: flex; flex-direction: column; gap: 8px; background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 12px 14px; cursor: pointer; }
.row.on { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent); }
.row.top { background: linear-gradient(180deg, var(--accent-soft), var(--surface) 60%); }
.r-badges { display: flex; gap: 6px; }
.badge { font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: 6px; }
.badge.ai { background: var(--accent); color: #fff; }
.badge.ok { background: var(--ok-soft); color: var(--ok); }
.r-main { display: grid; grid-template-columns: 36px minmax(120px, 1.3fr) minmax(80px, 1fr) minmax(70px, 0.8fr) minmax(80px, 1fr) auto auto; gap: 10px; align-items: center; }
.logo { width: 36px; height: 36px; border-radius: 10px; color: #fff; font-weight: 800; display: flex; align-items: center; justify-content: center; }
.logo.img, .pf-logo.img { background: #fff; border: 1px solid var(--line); box-sizing: border-box; padding: 2px; }
.r-air { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.r-airline { font-weight: 800; font-size: 14px; }
.r-end { display: flex; flex-direction: column; align-items: center; min-width: 0; text-align: center; }
.t { font-size: 20px; font-weight: 800; font-variant-numeric: tabular-nums; }
.nd { font-size: 11px; color: var(--price); vertical-align: super; margin-left: 2px; }
.r-mid { display: flex; flex-direction: column; align-items: center; gap: 2px; }
.line { width: 100%; height: 1px; background: var(--line); }
.r-price { display: flex; flex-direction: column; align-items: flex-end; white-space: nowrap; }
.price { font-size: 20px; font-weight: 800; color: var(--price); font-variant-numeric: tabular-nums; }
.pick-btn { margin: 0; }
.r-reason { display: flex; align-items: center; gap: 6px; font-size: 12px; padding: 6px 10px; border-radius: 8px; background: var(--surface-2); color: var(--ink); line-height: 1.5; }
.r-reason.warn { color: #c2560c; }
.foot { font-size: 12px; color: var(--muted); line-height: 1.6; }
.lhead { display: flex; justify-content: space-between; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.refresh-line { white-space: nowrap; }

.side { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.side-inner { display: flex; flex-direction: column; gap: 12px; }
.ph { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
.tag-soft { font-size: 11px; padding: 2px 8px; border-radius: 6px; background: var(--accent-soft); color: var(--accent); font-weight: 600; }
.best { display: flex; align-items: center; gap: 6px; padding: 8px 10px; border-radius: 10px; background: var(--ok-soft); font-weight: 700; font-size: 14px; cursor: pointer; }
.reason { display: flex; align-items: flex-start; gap: 6px; font-size: 13px; line-height: 1.5; }
.reason.bad { color: var(--price); }
.reason.note { color: var(--muted); }
.alt { font-size: 12px; cursor: pointer; }
.pf { display: grid; grid-template-columns: 22px 40px auto minmax(0, 1fr) auto; gap: 8px; align-items: center; font-size: 13px; }
.pf-logo { width: 22px; height: 22px; border-radius: 6px; color: #fff; font-size: 11px; font-weight: 800; display: flex; align-items: center; justify-content: center; }
.pf-name { font-weight: 600; }
.pf-price { font-weight: 800; font-variant-numeric: tabular-nums; }
.pf-price.best { color: var(--price); }
.pf-none { grid-column: 3 / 6; }
.pf-go { color: var(--accent); cursor: pointer; white-space: nowrap; border: 1px solid var(--accent); border-radius: 6px; padding: 1px 8px; font-size: 12px; }
.rules-grid { display: flex; flex-direction: column; gap: 6px; }
.cabins { display: flex; flex-direction: column; gap: 6px; }
.cabin { display: flex; flex-direction: column; gap: 2px; padding: 8px 10px; border-radius: 8px; background: var(--surface-2); }
.cabin.plain { outline: 1px solid var(--ok); }
.cabin-top { display: flex; align-items: baseline; gap: 6px; }
.cabin-cond { margin-left: auto; font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--surface); color: var(--muted); }
.rule { display: grid; grid-template-columns: 96px minmax(0, 1fr); gap: 8px; font-size: 13px; line-height: 1.5; }
.rule-k { color: var(--muted); }
.side .btn { margin: 0; }
.sheet-head { display: flex; justify-content: space-between; align-items: center; }
.d-x { cursor: pointer; padding: 4px; margin-left: auto; }

.side.sheet { position: fixed; inset: 0; z-index: 40; background: rgba(15, 23, 42, 0.45); display: flex; align-items: flex-end; }
.side.sheet .side-inner { width: 100%; max-height: 86vh; overflow-y: auto; background: var(--bg, var(--surface-2)); border-radius: 18px 18px 0 0; padding: 14px 14px calc(16px + env(safe-area-inset-bottom, 0px)); box-sizing: border-box; }

.mask { position: fixed; inset: 0; z-index: 50; background: rgba(15, 23, 42, 0.45); display: flex; align-items: flex-end; justify-content: center; }
.sheet-box { width: 100%; max-width: 540px; max-height: 88vh; overflow-y: auto; background: var(--surface); border-radius: 18px 18px 0 0; padding: 16px 16px calc(16px + env(safe-area-inset-bottom, 0px)); box-sizing: border-box; display: flex; flex-direction: column; gap: 12px; }
.sh-head { display: flex; align-items: center; gap: 8px; }
.sh-title { font-weight: 800; font-size: 16px; }
.preview-tag { font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--price); }
.swap-box { display: flex; flex-direction: column; gap: 4px; padding: 10px; border-radius: 10px; background: var(--surface-2); font-size: 14px; }
.swap-box .old { color: var(--muted); text-decoration: line-through; }
.swap-box .new { font-weight: 800; }
.checks { display: flex; flex-direction: column; gap: 6px; }
.acts { display: grid; grid-template-columns: 2fr 1fr; gap: 8px; }
.acts .btn { margin: 0; }

@media (max-width: 1023px) {
  .r-main { grid-template-columns: 32px minmax(0, 1fr) minmax(0, 1fr) auto; grid-template-areas: "logo air air price" "dep dep mid arr"; }
  .logo { grid-area: logo; width: 32px; height: 32px; }
  .r-air { grid-area: air; }
  .r-price { grid-area: price; }
  .r-dep { grid-area: dep; }
  .r-mid { grid-area: mid; }
  .r-arr { grid-area: arr; }
  /* 手机上推荐卡放在列表前面 */
  .side:not(.sheet) { order: -1; }
  .pick-btn { display: none; }
  .sbtn { width: 100%; justify-content: center; padding: 10px; }
  .hero-title { font-size: 18px; }
}
@media (min-width: 1024px) {
  .cal-row { display: grid; grid-template-columns: minmax(0, 1fr) 340px; }
  .grid { display: grid; grid-template-columns: minmax(0, 1fr) 420px; align-items: start; gap: 16px; }
  .side { position: sticky; top: 20px; }
  .mask { align-items: center; }
  .sheet-box { border-radius: 18px; }
}
</style>
