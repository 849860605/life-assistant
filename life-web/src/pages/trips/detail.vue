<template>
  <Shell active="plan" :show-recent="false">
  <view v-if="wide && days.length" class="editor">
    <view class="ed-head">
      <view class="back" @click="goBack"><Icon name="back" :size="22" color="var(--ink)" /></view>
      <text class="ed-title">{{ trip?.title }}</text>
      <text class="tag" :class="trip?.status === 'DRAFT' ? '' : 'ok'">{{ trip?.status === "DRAFT" ? "方案 · 未保存" : "已保存的行程" }}</text>
      <text v-if="!isCurrent" class="tag">正在看第 {{ detail?.version.version }} 版</text>
      <button v-if="!isCurrent" class="btn small" @click="useVersion">用这一版</button>
      <button v-if="versions.length > 1" class="btn danger small" @click="removeVersion">删除第 {{ detail?.version.version }} 版</button>
      <view class="ed-actions">
        <FavButton v-if="trip?.status === 'DRAFT'" :item="planFav" label="收藏方案" />
        <view class="ghost-act" @click="openInfo"><Icon name="more" :size="15" color="var(--ink)" />编辑</view>
        <view class="ghost-act" @click="share"><Icon name="send" :size="15" color="var(--ink)" />分享</view>
        <view class="ghost-act" @click="downloadPdf"><Icon name="layers" :size="15" color="var(--ink)" />下载PDF</view>
        <button class="btn ghost small" @click="changeNeeds">修改需求</button>
        <button v-if="trip?.status === 'DRAFT'" class="btn small" @click="confirmTrip">保存为行程</button>
      </view>
    </view>
    <view class="ed-chips">
      <view class="ed-chip"><Icon name="calendar" :size="15" color="var(--accent)" />{{ cnDate(trip?.startDate) }} – {{ cnDate(trip?.endDate) }}（{{ days.length }}天{{ Math.max(days.length - 1, 0) }}晚）</view>
      <view v-if="trip?.destination" class="ed-chip"><Icon name="pin" :size="15" color="var(--accent)" />{{ trip.destination }}</view>
      <view class="ed-chip" @click="openInfo"><Icon name="users" :size="15" color="var(--accent)" />{{ trip?.people ? `${trip.people} 人` : "人数未填" }}</view>
      <view class="ed-chip"><Icon name="wallet" :size="15" color="var(--accent)" />景点餐饮约 ¥{{ totalCost }}/人<text v-if="stayCost"> · 住宿约 ¥{{ stayCost }}/间</text></view>
      <view v-if="versions.length > 1" class="ed-chip ver-chip">
        <text class="muted">版本</text>
        <text v-for="v in versions" :key="v.version" class="ver" :class="{ on: v.version === detail?.version.version }" @click="load(v.version)">{{ v.version }}</text>
      </view>
      <view v-if="isCurrent" class="ed-chip mode-chip">
        <text class="muted">出行</text>
        <text v-for="m in MODES" :key="m.v" class="ver" :class="{ on: transport === m.v }" @click="setTransport(m.v)">{{ m.label }}</text>
      </view>
      <text v-if="geoLoading" class="ed-tag">正在补地图信息…</text>
    </view>
    <view v-if="saved" class="saved">
      <Icon name="checkCircle" :size="16" color="var(--ok)" />
      <text>{{ saved.note }}，已保存为第 {{ saved.to }} 版</text>
      <text class="saved-link" @click="go(`/pages/plan/updated?id=${tripId}&from=${saved.from}&to=${saved.to}`)">查看前后对比 ›</text>
      <view class="saved-x" @click="saved = null"><Icon name="close" :size="14" color="var(--muted)" /></view>
    </view>
    <view v-if="trip?.status === 'DRAFT'" class="notice warn draft-note">这是星络生成的方案，还没保存为行程。可以先调整，满意了点「保存为行程」。</view>
    <view class="ttabs" role="tablist">
      <view v-for="t in TTABS" :key="t.key" class="ttab" :class="{ on: ttab === t.key }" role="tab" @click="setTab(t.key)">{{ t.label }}</view>
    </view>

    <view v-if="ttab === 'schedule'" class="ed-cols">
      <!-- 左：当天时间线 -->
      <view class="ed-left">
        <view class="ed-days">
          <view v-for="(d, i) in days" :key="d.date" class="ed-day" :class="{ on: i === dayIndex }" @click="dayIndex = i">
            <text class="ed-day-no">D{{ i + 1 }}</text><text class="ed-day-date">{{ shortDate(d.date) }}</text>
          </view>
        </view>
        <view class="day-h">
          <text class="day-h-no">D{{ dayIndex + 1 }}</text>
          <text class="day-h-date">{{ cnDate(day?.date) }}</text>
          <text class="day-h-theme">{{ day?.theme }}</text>
        </view>
        <view class="day-h-sub">{{ day?.items.filter((x) => x.type !== "TRANSPORT").map((x) => x.name).join(" · ") }}</view>

        <view class="tl">
          <template v-for="item in day?.items" :key="item.id">
            <view class="tl-item">
              <view class="tl-time">{{ item.start }}</view>
              <view class="tl-dot" />
              <view class="tl-card" :class="{ locked: item.locked }">
                <view class="tl-main">
                  <image v-if="item.photo && !broken[item.id]" class="tl-img" @error="broken[item.id] = true" :src="item.photo" mode="aspectFill" />
                  <view v-else class="tl-img ph" :class="[item.type, { mini: MINI.includes(item.type) }]"><Icon :name="itemIcon(item)" :size="MINI.includes(item.type) ? 18 : 24" :color="MINI.includes(item.type) ? 'var(--accent)' : '#fff'" /></view>
                  <view class="tl-body">
                    <view class="tl-name">
                      <text :class="{ link: itemLink(item) || pendingFlight(item) }" @click="pendingFlight(item) ? changeFlight() : openUrl(itemLink(item))">{{ item.name }}</text>
                      <text v-if="item.type === 'SPOT' && (item.rating || 0) >= 4.8" class="must">高分景点</text>
                      <text v-if="item.locked" class="kept-tag">已保留</text>
                      <text v-if="isBooked(item.id)" class="booked-tag">已预约</text>
                      <text v-if="isDone(item.id)" class="booked-tag">{{ doneText(item.type, true) }}</text>
                    </view>
                    <view class="tl-note">{{ item.note }}</view>
                    <view class="tl-facts">
                      <text v-if="duration(item)"><Icon name="calendar" :size="12" color="var(--muted)" /> {{ duration(item) }}</text>
                      <text v-if="item.rating" class="star">★ {{ item.rating }}</text>
                      <text v-if="item.address"><Icon name="pin" :size="12" color="var(--muted)" /> {{ item.address }}</text>
                      <text v-if="item.cost" class="cost">¥{{ item.cost }}/人</text>
                      <text v-if="day && flightChecking(itinerary, item, day.date)" class="checking">其他平台比价中…</text>
                      <text v-if="item.type === 'HOTEL' && itemPrice(item)" class="cost" :class="{ quiet: itemPrice(item)?.price == null }">{{ priceText(itemPrice(item)) }}</text>
                    </view>
                    <view v-if="EDITABLE.includes(item.type)" class="amap-links">
                      <text class="amap" @click.stop="openUrl(amapUrl(item, city))"><Icon name="pin" :size="12" color="var(--accent)" /> 高德地图</text>
                      <text class="amap" @click.stop="openUrl(amapNavUrl(item, city))"><Icon name="nav" :size="12" color="var(--accent)" /> 导航</text>
                    </view>
                  </view>
                  <view v-if="isCurrent" class="tl-more" @click="more(item)"><Icon name="more" :size="16" color="var(--muted)" /></view>
                </view>
                <FlightStatusCard v-if="day && flightNoOf(item)" :flight-no="flightNoOf(item)!" :date="day.date" />
                <view v-if="isCurrent && (BOOKABLE.includes(item.type) || doneable(item.type))" class="tl-acts">
                  <template v-if="EDITABLE.includes(item.type)">
                    <view class="a keep" :class="{ on: item.locked }" @click="toggleKeep(item)"><Icon name="heart" :size="13" :fill="!!item.locked" color="var(--ok)" />{{ item.locked ? "已保留" : "喜欢，保留" }}</view>
                    <view class="a swap" @click="openReplace(item, 'VISITED')"><Icon name="refresh" :size="13" color="var(--accent)" />以前去过，换一个</view>
                    <view class="a" @click="openReplace(item, 'DISLIKE')"><Icon name="close" :size="13" color="var(--muted)" />不感兴趣</view>
                  </template>
                  <view v-if="flightNoOf(item) && !isBooked(item.id)" class="a swap" @click="changeFlight"><Icon name="refresh" :size="13" color="var(--accent)" />比价换航班</view>
                  <view v-if="pendingFlight(item)" class="a swap" @click="changeFlight"><Icon name="plane" :size="13" color="var(--accent)" />去选航班</view>
                  <view v-if="BOOKABLE.includes(item.type)" class="a book" :class="{ on: isBooked(item.id) }" @click="toggleBooked(item.id, item.name)"><Icon name="checkCircle" :size="13" color="var(--ok)" />{{ isBooked(item.id) ? "已预约" : "标记已预约" }}</view>
                  <view v-if="doneable(item.type)" class="a book" :class="{ on: isDone(item.id) }" @click="toggleDone(item)"><Icon name="check" :size="13" color="var(--ok)" />{{ doneText(item.type, isDone(item.id)) }}</view>
                </view>
              </view>
            </view>
            <view v-if="legFrom(day, item.id)" class="tl-leg"><LegRow :leg="legFrom(day, item.id)!" :day="day" :stays="stays" :editable="isCurrent" :busy="modeBusy" @mode="setLegMode(legFrom(day, item.id)!, $event)" /></view>
          </template>
        </view>
        <view class="tl-add" @click="addStop"><Icon name="plus" :size="16" color="var(--accent)" />添加景点 / 活动</view>
      </view>

      <!-- 中：当天概览、美食、酒店 -->
      <view class="ed-mid">
        <view class="day-hero">
          <image v-if="dayHero" class="day-hero-img" :src="dayHero" mode="aspectFill" />
          <view v-else class="day-hero-img hero-ph" />
          <view class="overview">
            <view class="ov-title">当天行程概览</view>
            <view class="ov-grid">
              <view class="ov"><Icon name="pin" :size="18" color="var(--accent)" /><view><view class="ov-k">景点</view><view class="ov-v">{{ dayStats.spots }} 个</view></view></view>
              <view class="ov"><Icon name="food" :size="18" color="var(--accent)" /><view><view class="ov-k">美食</view><view class="ov-v">{{ dayStats.foods }} 个</view></view></view>
              <view class="ov"><Icon name="calendar" :size="18" color="var(--accent)" /><view><view class="ov-k">总时长</view><view class="ov-v">{{ dayStats.hours }}</view></view></view>
              <view class="ov"><Icon name="car" :size="18" color="var(--accent)" /><view><view class="ov-k">路上</view><view class="ov-v">{{ travel.minutes ? duration2(travel.minutes) : "—" }}</view></view></view>
            </view>
          </view>
        </view>

        <view class="panel">
          <view class="panel-head"><text class="panel-title"><Icon name="food" :size="16" color="var(--accent)" /> 行程中的美食推荐</text><text class="more" @click="openFood">顺路找餐厅 ›</text></view>
          <view class="muted small">{{ anchor ? `${anchor.name} 附近，高德评分从高到低` : "这一天还没有带位置的景点" }}</view>
          <view v-if="nearLoading" class="muted small pad">正在找附近的餐厅…</view>
          <view v-for="f in foods.slice(0, 3)" :key="f.id" class="food">
            <image v-if="f.photo && !broken[f.id]" @error="broken[f.id] = true" class="food-img" :src="f.photo" mode="aspectFill" />
            <view v-else class="food-img ph FOOD"><Icon name="food" :size="22" color="#fff" /></view>
            <view class="food-body">
              <view class="food-name">{{ f.name }}</view>
              <view class="food-meta"><text v-if="f.rating" class="star">★ {{ f.rating }}</text> <text class="muted">高德评分</text></view>
              <view class="food-tags"><text class="ftag">{{ f.category }}</text><text v-if="f.cost" class="ftag">人均 ¥{{ f.cost }}</text></view>
              <view v-if="f.distance != null" class="muted small">距离{{ anchor?.name }} {{ distanceText(f.distance) }}</view>
            </view>
            <button v-if="isCurrent" class="btn ghost small add" :class="{ done: added[f.id] }" :disabled="added[f.id]" @click="addPlace(f, 'FOOD')">{{ added[f.id] ? "已加入" : "加入行程" }}</button>
          </view>
        </view>

        <view class="panel">
          <view class="panel-head"><text class="panel-title"><Icon name="bed" :size="16" color="var(--accent)" /> 当日酒店</text></view>
          <StayCard v-if="dayStay" :key="dayStay.id + dayStay.name" :stay="dayStay" :trip-id="tripId" :editable="isCurrent" :booked="isBooked(dayStay.id)" :checking="stayChecking(itinerary, dayStay)" @saved="onSaved" @updated="detail = $event" />
          <template v-else-if="hotel">
            <view class="muted small"><Icon name="checkCircle" :size="12" color="var(--ok)" /> {{ cnDate(day?.date) }} 住这里<text v-if="hotel.address"> · {{ hotel.address }}</text></view>
            <view class="food">
              <image v-if="hotel.photo" class="food-img" :src="hotel.photo" mode="aspectFill" />
              <view v-else class="food-img ph"><Icon name="bed" :size="22" color="#fff" /></view>
              <view class="food-body">
                <view class="food-name">{{ hotel.name }}</view>
                <view class="food-meta"><text v-if="hotel.rating" class="star">★ {{ hotel.rating }}</text> <text v-if="hotel.rating" class="muted">高德评分</text></view>
                <view v-if="hotel.note" class="muted small">{{ hotel.note }}</view>
              </view>
              <view class="hotel-side">
                <text v-if="hotel.cost" class="price">¥{{ hotel.cost }}</text>
                <button class="btn ghost small add" @click="go(`/pages/search/hotel?trip=${tripId}`)">{{ isCurrent ? "比价换酒店" : "去比价" }}</button>
              </view>
            </view>
          </template>
          <template v-else>
            <view class="muted small">行程里还没安排酒店，下面是{{ anchor?.name || "附近" }}的酒店（高德）<text class="more" @click="go(`/pages/search/hotel?trip=${tripId}`)">比价并设为住宿 ›</text></view>
            <view v-for="h in hotels.slice(0, 2)" :key="h.id" class="food">
              <image v-if="h.photo && !broken[h.id]" @error="broken[h.id] = true" class="food-img" :src="h.photo" mode="aspectFill" />
              <view v-else class="food-img ph"><Icon name="bed" :size="22" color="#fff" /></view>
              <view class="food-body">
                <view class="food-name">{{ h.name }}</view>
                <view class="food-meta"><text v-if="h.rating" class="star">★ {{ h.rating }}</text> <text class="muted">{{ h.category }}</text></view>
                <view v-if="h.distance != null" class="muted small">距离 {{ distanceText(h.distance) }}</view>
                <view v-if="nearbyPrice(h)" class="small" :class="nearbyPrice(h)?.price != null ? 'cost' : 'muted'">{{ priceText(nearbyPrice(h)) }}</view>
              </view>
              <button v-if="isCurrent" class="btn ghost small add" :class="{ done: added[h.id] }" :disabled="added[h.id]" @click="addPlace(h, 'HOTEL')">{{ added[h.id] ? "已加入" : "加入行程" }}</button>
            </view>
          </template>
        </view>
      </view>

      <!-- 右：地图、路线、附近推荐、建议 -->
      <view class="ed-right">
        <view class="panel">
          <view class="seg">
            <text class="seg-item" :class="{ on: rightTab === 'map' }" @click="rightTab = 'map'">地图路线</text>
            <text class="seg-item" :class="{ on: rightTab === 'time' }" @click="rightTab = 'time'">时间安排</text>
          </view>
          <template v-if="rightTab === 'map'">
            <image v-if="dayMap" class="r-map" :src="dayMap" mode="aspectFill" @click="openMap" />
            <view v-else class="r-map empty-map"><Icon name="nav" :size="28" color="var(--muted)" /><text class="muted small">{{ geoLoading ? "正在补坐标…" : "这一天的地点在高德上没找到坐标" }}</text></view>
            <view class="route-info">
              <view class="ri-title">路线信息</view>
              <view class="ri-grid">
                <view class="ri"><Icon name="route" :size="18" color="var(--accent)" /><view><view class="ov-k">总距离</view><view class="ov-v">{{ travel.km ? `约 ${travel.km} 公里` : "—" }}</view></view></view>
                <view class="ri"><Icon name="car" :size="18" color="var(--accent)" /><view><view class="ov-k">路上</view><view class="ov-v">{{ travel.minutes ? duration2(travel.minutes) : "—" }}</view></view></view>
                <view class="ri"><Icon name="wallet" :size="18" color="var(--accent)" /><view><view class="ov-k">交通费</view><view class="ov-v">{{ fareText }}</view></view></view>
              </view>
            </view>
          </template>
          <view v-else class="times">
            <view v-for="item in day?.items" :key="item.id" class="time-row"><text class="time-k">{{ item.start }}–{{ item.end }}</text><text>{{ item.name }}</text></view>
          </view>
        </view>

        <view class="panel">
          <view class="panel-head"><text class="panel-title"><Icon name="sparkle" :size="16" color="var(--accent)" /> 附近其他推荐景点</text></view>
          <view v-if="!spots.length" class="muted small pad">{{ nearLoading ? "正在找…" : anchor ? "附近没找到别的景点" : "这一天还没有带位置的景点" }}</view>
          <view class="near-grid">
            <view v-for="r in spots.slice(0, 3)" :key="r.id" class="near">
              <image v-if="r.photo && !broken[r.id]" @error="broken[r.id] = true" class="near-img" :src="r.photo" mode="aspectFill" />
              <view v-else class="near-img ph"><Icon name="pin" :size="20" color="#fff" /></view>
              <view class="near-name">{{ r.name }}</view>
              <view class="near-meta"><text v-if="r.rating" class="star">★ {{ r.rating }}</text><text v-if="r.distance != null"> · {{ distanceText(r.distance) }}</text></view>
              <view v-if="isCurrent" class="near-btn" :class="{ done: added[r.id] }" @click="!added[r.id] && addPlace(r, 'SPOT')">{{ added[r.id] ? "已加入" : "加入这一天" }}</view>
            </view>
          </view>
        </view>

        <view class="panel">
          <view class="panel-head"><text class="panel-title"><Icon name="chat" :size="16" color="var(--accent)" /> 调整建议</text></view>
          <view v-for="sg in suggestions" :key="sg.text" class="sg"><Icon :name="sg.warn ? 'flag' : 'checkCircle'" :size="14" :color="sg.warn ? 'var(--price)' : 'var(--ok)'" /><text>{{ sg.text }}</text></view>
          <button class="btn ghost regen" @click="regenerate">让助手重新安排当天</button>
        </view>
      </view>
    </view>
    <TripExpenses v-else-if="ttab === 'expense' && trip" :trip="trip" :estimate="{ perPerson: totalCost, stayPerRoom: stayCost }" @edit-info="openInfo" />
    <TripBookings v-else-if="trip" :trip-id="tripId" />

    <ReplaceModal v-if="replacing" :trip-id="tripId" :item="replacing" :day-no="dayIndex + 1" :reason="replaceReason" :city="city" :check-in="replaceDates[0]" :check-out="replaceDates[1]" @close="replacing = null" @saved="onSaved" />
  </view>

  <view v-else class="page">
    <view v-if="trip" class="head">
      <view class="title-row">
        <text class="title">{{ trip.title }}</text>
        <text class="tag" :class="trip.status === 'DRAFT' ? '' : 'ok'">{{ trip.status === "DRAFT" ? "方案 · 未保存" : "已保存" }}</text>
        <text v-if="!isCurrent" class="tag">正在看第 {{ detail?.version.version }} 版</text>
      </view>
      <view class="meta">{{ trip.destination }} · {{ cnDate(trip.startDate) }} – {{ cnDate(trip.endDate) }}{{ trip.people ? ` · ${trip.people} 人` : "" }}</view>
      <view class="m-acts">
        <FavButton v-if="trip.status === 'DRAFT'" :item="planFav" label="收藏方案" />
        <text class="m-pdf" @click="openInfo">编辑</text>
        <text class="m-pdf" @click="share">分享</text>
        <text class="m-pdf" @click="downloadPdf">导出 PDF</text>
      </view>
      <view v-if="trip.status === 'DRAFT'" class="notice warn">这是星络生成的方案，还没保存为行程。</view>
    </view>
    <view v-if="saved" class="saved">
      <Icon name="checkCircle" :size="16" color="var(--ok)" />
      <text>已保存为第 {{ saved.to }} 版</text>
      <text class="saved-link" @click="go(`/pages/plan/updated?id=${tripId}&from=${saved.from}&to=${saved.to}`)">前后对比 ›</text>
      <view class="saved-x" @click="saved = null"><Icon name="close" :size="14" color="var(--muted)" /></view>
    </view>

    <view class="ttabs m-ttabs" role="tablist">
      <view v-for="t in TTABS" :key="t.key" class="ttab" :class="{ on: ttab === t.key }" role="tab" @click="setTab(t.key)">{{ t.label }}</view>
    </view>
    <TripExpenses v-if="ttab === 'expense' && trip" :trip="trip" :estimate="{ perPerson: totalCost, stayPerRoom: stayCost }" @edit-info="openInfo" />
    <TripBookings v-else-if="ttab === 'booking' && trip" :trip-id="tripId" />

    <template v-if="days.length && ttab === 'schedule'">
      <view class="m-links">
        <text v-for="t in SHORTCUTS" :key="t" class="m-link" @click="switchTab(t)">{{ t }}</text>
      </view>
      <scroll-view scroll-x class="days">
        <view class="days-inner">
          <view v-for="(d, i) in days" :key="d.date" class="day-tab" :class="{ active: i === dayIndex }" @click="dayIndex = i">
            <text class="day-no">D{{ i + 1 }}</text>
            <text class="day-date">{{ shortDate(d.date) }}</text>
          </view>
        </view>
      </scroll-view>

      <view v-if="day" class="timeline">
        <view v-if="isCurrent" class="m-mode">
          <text class="muted small">出行方式</text>
          <view class="m-mode-seg"><text v-for="m in MODES" :key="m.v" class="m-mode-item" :class="{ on: transport === m.v }" @click="setTransport(m.v)">{{ m.label }}</text></view>
        </view>
        <view v-if="day.theme" class="day-theme">{{ day.theme }}</view>
        <view v-for="(item, i) in day.items" :key="item.id" class="stop">
          <view class="rail">
            <view class="marker" :class="item.type">{{ i + 1 }}</view>
          </view>
          <view class="stop-card">
            <view class="stop-main">
              <image v-if="item.photo && !broken[item.id]" class="thumb" @error="broken[item.id] = true" :src="item.photo" mode="aspectFill" />
              <view v-else class="thumb placeholder" :class="[item.type, { mini: MINI.includes(item.type) }]"><Icon :name="itemIcon(item)" :size="MINI.includes(item.type) ? 18 : 22" :color="MINI.includes(item.type) ? 'var(--accent)' : '#fff'" /></view>
              <view class="stop-body">
                <view class="stop-name" :class="{ link: item.url || pendingFlight(item) }" @click="pendingFlight(item) ? changeFlight() : openUrl(item.url)">{{ item.name }}<text v-if="isBooked(item.id)" class="booked-tag">已预约</text></view>
                <text v-if="doneable(item.type)" class="done-btn" :class="{ on: isDone(item.id) }" @click.stop="toggleDone(item)">{{ isDone(item.id) ? "✓ " : "" }}{{ doneText(item.type, isDone(item.id)) }}</text>
                <view class="stop-time">{{ item.start }}<text v-if="item.end && item.end !== item.start"> - {{ item.end }}</text><text v-if="item.rating" class="star"> · ★{{ item.rating }}</text><text v-if="item.cost" class="cost"> · ¥{{ item.cost }}/人</text><text v-if="day && flightChecking(itinerary, item, day.date)" class="checking"> · 其他平台比价中…</text></view>
                <view v-if="item.note" class="stop-note">{{ item.note }}</view>
                <FlightStatusCard v-if="day && flightNoOf(item)" :flight-no="flightNoOf(item)!" :date="day.date" compact />
                <view v-if="EDITABLE.includes(item.type)" class="amap-links">
                  <text class="amap" @click.stop="openUrl(amapUrl(item, city))"><Icon name="pin" :size="12" color="var(--accent)" /> 高德地图</text>
                  <text class="amap" @click.stop="openUrl(amapNavUrl(item, city))"><Icon name="nav" :size="12" color="var(--accent)" /> 导航</text>
                </view>
              </view>
            </view>
            <view v-if="isCurrent && BOOKABLE.includes(item.type)" class="stop-actions">
              <view class="act book" :class="{ on: isBooked(item.id) }" @click="toggleBooked(item.id, item.name)">
                <Icon name="checkCircle" :size="13" color="var(--ok)" />
                <text>{{ isBooked(item.id) ? "已预约" : "标记已预约" }}</text>
              </view>
              <view v-if="flightNoOf(item) && !isBooked(item.id)" class="act" @click="changeFlight">
                <Icon name="refresh" :size="13" color="var(--ink)" />
                <text>比价换航班</text>
              </view>
              <view v-if="pendingFlight(item)" class="act" @click="changeFlight">
                <Icon name="plane" :size="13" color="var(--accent)" />
                <text>去选航班</text>
              </view>
            </view>
            <view v-if="isCurrent && EDITABLE.includes(item.type)" class="stop-actions">
              <view class="act keep" :class="{ on: item.locked }" @click="toggleKeep(item)">
                <Icon name="heart" :size="13" :fill="!!item.locked" color="var(--ok)" />
                <text>{{ item.locked ? "已保留" : "喜欢，保留" }}</text>
              </view>
              <view class="act" @click="openReplace(item, 'VISITED')">
                <Icon name="refresh" :size="13" color="var(--ink)" />
                <text>换一个</text>
              </view>
              <view class="act ghost" @click="openReplace(item, 'DISLIKE')">
                <Icon name="close" :size="13" color="var(--muted)" />
                <text>不感兴趣</text>
              </view>
              <view class="grip" @click="edit"><Icon name="grip" :size="16" color="var(--muted)" /></view>
            </view>
            <view v-if="legFrom(day, item.id)" class="stop-leg"><LegRow :leg="legFrom(day, item.id)!" :day="day" :stays="stays" :editable="isCurrent" :busy="modeBusy" prefix="到下一站 " @mode="setLegMode(legFrom(day, item.id)!, $event)" /></view>
          </view>
        </view>
        <view class="add" @click="addStop"><Icon name="plus" :size="16" color="var(--accent)" /><text>添加景点/活动</text></view>
        <view v-if="dayStay" class="m-stay">
          <view class="m-stay-title"><Icon name="bed" :size="15" color="var(--accent)" /> 今晚住</view>
          <StayCard :key="dayStay.id + dayStay.name" :stay="dayStay" :trip-id="tripId" :editable="isCurrent" :booked="isBooked(dayStay.id)" :checking="stayChecking(itinerary, dayStay)" @saved="onSaved" @updated="detail = $event" />
        </view>
      </view>

      <view v-if="tips" class="tips">
        <view class="tips-title">注意事项</view>
        <MarkdownView :text="tips" />
      </view>
    </template>
    <view v-else-if="detail && ttab === 'schedule'" class="card body">
      <MarkdownView :text="detail.version.content || ''" />
    </view>

    <view v-if="versions.length > 1" class="versions">
      <text class="muted small">历史版本：</text>
      <text v-for="v in versions" :key="v.version" class="version" :class="{ active: detail && v.version === detail.version.version }" @click="load(v.version)">
        第 {{ v.version }} 版{{ v.version === trip?.currentVer ? "（在用）" : "" }}
      </text>
      <text class="muted small">点版本号切换，删除只删正在看的那一版</text>
    </view>

    <view v-if="events.length" class="card reminders">
      <view class="tips-title">关联的提醒</view>
      <view v-for="e in events" :key="e.id" class="reminder">
        <text class="reminder-time">{{ eventTime(e.startAt) }}</text>
        <text>{{ e.title }}</text>
      </view>
    </view>

    <view v-if="trip" class="bottom">
      <button v-if="!isCurrent" class="btn" @click="useVersion">用这一版</button>
      <button v-else-if="trip.status === 'DRAFT'" class="btn" @click="confirmTrip">保存为行程</button>
      <button v-if="versions.length > 1" class="btn danger" @click="removeVersion">删除第 {{ detail?.version.version }} 版</button>
      <button v-else class="btn danger" @click="removeTrip">删除行程</button>
    </view>
  </view>
  <view v-if="info" class="info-mask" @click.self="info = null">
    <view class="info-sheet" role="dialog" aria-label="编辑行程信息">
      <view class="info-title">编辑行程信息</view>
      <view class="info-field"><text class="info-label">名称</text><input id="trip-title" v-model="info.title" class="input" maxlength="60" /></view>
      <view class="info-field"><text class="info-label">出发城市</text><input id="trip-origin" v-model="info.origin" class="input" maxlength="32" placeholder="例如：深圳，查机票时自动填上" /></view>
      <view class="info-row">
        <view class="info-field"><text class="info-label">人数</text><input id="trip-people" v-model="info.people" class="input" type="number" maxlength="2" placeholder="可不填" /></view>
        <view class="info-field grow"><text class="info-label">总预算</text><input id="trip-budget" v-model="info.budget" class="input" type="digit" placeholder="可不填" /></view>
        <view class="info-field">
          <text class="info-label">币种</text>
          <picker :range="CURRENCIES" :value="CURRENCIES.indexOf(info.currency)" @change="info.currency = CURRENCIES[Number($event.detail.value)]"><view class="input info-picker">{{ info.currency }}</view></picker>
        </view>
      </view>
      <view class="muted small">日期和每天的安排在对话里让星络改，或者用"修改需求"。</view>
      <view v-if="infoError" class="info-err">{{ infoError }}</view>
      <view class="info-acts">
        <button class="btn ghost" @click="info = null">取消</button>
        <button class="btn" :disabled="infoSaving" @click="saveInfo">{{ infoSaving ? "保存中…" : "保存" }}</button>
      </view>
    </view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { api } from "../../api";
import { API_BASE, showError } from "../../api/http";
import { ensureMediaTicket } from "../../utils/media";
import type { ChangeReason, ItineraryDay, ItineraryItem, ItineraryLeg, Place, ScheduleEvent, Stay, TripDetail, TripVersion } from "../../api/types";
import Icon from "../../components/Icon.vue";
import FlightStatusCard from "../../components/FlightStatusCard.vue";
import MarkdownView from "../../components/MarkdownView.vue";
import ReplaceModal from "../../components/ReplaceModal.vue";
import StayCard from "../../components/StayCard.vue";
import LegRow from "../../components/LegRow.vue";
import Shell from "../../components/Shell.vue";
import TripBookings from "../../components/TripBookings.vue";
import FavButton from "../../components/FavButton.vue";
import TripExpenses from "../../components/TripExpenses.vue";
import { CURRENCIES } from "../../utils/money";
import { useWide } from "../../utils/screen";
import { doneable, doneText, hm, mdw, openUrl, shortDate } from "../../utils/format";
import { hotelPrice, loadHotelPrices, priceText, stayDates, type HotelPrice } from "../../utils/hotelPrice";
import { anyChecking, flightChecking, flightNoOf, pollWhileChecking, stayChecking } from "../../utils/priceCheck";
import { amapNavUrl, amapUrl, cityOf, dayMapUrl, dayTravel, distanceText, duration as duration2, legFrom, located, needsGeo } from "../../utils/place";

/**
 * 行程详情（原型④）：按天切换的时间线，每项可以保留、换一个、不感兴趣，改完存成新版本。
 * 坐标、照片、路程由后端从高德补；电脑上中栏和右栏的美食、酒店、附近景点按当天第一个景点的位置从高德查。
 */
/** 固定三个标签：日程、费用、预订信息；当前标签写在链接里（?tab=），刷新、返回、分享链接都能回到同一个 */
const TTABS = [
  { key: "schedule", label: "日程" },
  { key: "expense", label: "费用" },
  { key: "booking", label: "预订信息" },
] as const;
type TripTab = (typeof TTABS)[number]["key"];
/** 手机上日程标签里的快捷入口：地图、美食、机票、门票（各自是单独的页面） */
const SHORTCUTS = ["地图路线", "美食推荐", "机票比价", "景点门票"];
const TYPE_ICON: Record<string, string> = { SPOT: "pin", FOOD: "food", HOTEL: "bed", TRANSPORT: "car", FREE: "sparkle" };
/** 这些类型没有照片时只放一个小圆图标，不占一大块 */
const MINI = ["TRANSPORT", "FREE"];
/** 这些类型能保留、换、删；交通和自由活动不能 */
const EDITABLE = ["SPOT", "FOOD", "HOTEL"];
/** 这些类型能标记已预约（交通是机票、火车票这类） */
const BOOKABLE = ["SPOT", "FOOD", "HOTEL", "TRANSPORT"];
/** 整个行程默认的出行方式 */
const MODES = [{ v: "TRANSIT", label: "地铁公交" }, { v: "DRIVE", label: "打车" }] as const;

const tripId = ref(0);
const detail = ref<TripDetail | null>(null);
const versions = ref<TripVersion[]>([]);
const events = ref<ScheduleEvent[]>([]);
const tab = ref("行程总览");
const ttab = ref<TripTab>("schedule");
/** 编辑行程信息（名称、人数、预算）的表单 */
const info = ref<{ title: string; origin: string; people: string; budget: string; currency: string } | null>(null);
const infoError = ref("");
const infoSaving = ref(false);
const dayIndex = ref(0);
const wide = useWide();
const rightTab = ref("map");
const replacing = ref<ItineraryItem | null>(null);
const replaceReason = ref<ChangeReason | null>(null);
const geoLoading = ref(false);
/** 最近一次修改：改了什么、从第几版到第几版，用来提示和跳前后对比 */
const saved = ref<{ note: string; from: number; to: number } | null>(null);
const foods = ref<Place[]>([]);
const spots = ref<Place[]>([]);
const hotels = ref<Place[]>([]);
const nearLoading = ref(false);
/** 这次打开页面后已加入行程的地点 id */
const added = reactive<Record<string, boolean>>({});
/** 加载失败的高德照片，改显示占位图 */
const broken = reactive<Record<string, boolean>>({});
let loaded = false;

const trip = computed(() => detail.value?.trip);
/** 收藏方案用的信息（只是收藏，不等于保存为行程） */
const planFav = computed(() => ({
  kind: "PLAN" as const,
  refKey: `trip:${tripId.value}`,
  title: trip.value?.title || "方案",
  subtitle: trip.value?.startDate ? `${cnDate(trip.value.startDate)} – ${cnDate(trip.value.endDate)}` : null,
  city: trip.value?.destination || null,
}));
/** 行程所在城市，没有坐标的地点在高德里按名称搜时用 */
const city = computed(() => cityOf(trip.value?.destination));
/** 只有当前版本能改；看历史版本时隐藏操作按钮 */
const isCurrent = computed(() => !!detail.value && detail.value.version.version === detail.value.trip.currentVer);
const days = computed<ItineraryDay[]>(() => detail.value?.version.itinerary?.days || []);
const day = computed(() => days.value[dayIndex.value]);
const tips = computed(() => detail.value?.version.itinerary?.tips);
const totalCost = computed(() => days.value.reduce((s, d) => s + dayCost(d), 0));
const travel = computed(() => dayTravel(day.value));
const walkLegs = computed(() => (day.value?.legs || []).filter((l) => l.mode === "WALK").length);
/** 当天交通费：地铁公交每人票价加打车费，都没有时显示步行段数 */
const fareText = computed(() => {
  const parts = [];
  if (travel.value.fare != null) parts.push(`地铁公交 ¥${travel.value.fare}/人`);
  if (travel.value.taxi != null) parts.push(`打车 ¥${travel.value.taxi}`);
  return parts.length ? parts.join(" + ") : walkLegs.value ? "全程步行" : "—";
});
/** 行程默认怎么走，没设过按地铁公交 */
const transport = computed(() => (detail.value?.version.itinerary?.transport === "DRIVE" ? "DRIVE" : "TRANSIT"));
/** 正在切换出行方式，避免连点 */
const modeBusy = ref(false);
const dayMap = computed(() => dayMapUrl(day.value, 380, 270));
/** 当天大图：第一张有照片的景点，没有就用任意有照片的项 */
const dayHero = computed(() => {
  const items = day.value?.items || [];
  return (items.find((x) => x.type === "SPOT" && x.photo) || items.find((x) => x.photo))?.photo || "";
});
/** 找附近美食、景点的中心：当天第一个有坐标的景点，没有就用第一个有坐标的项 */
const anchor = computed(() => {
  const list = located(day.value);
  return list.find((x) => x.type === "SPOT") || list[0];
});
const stays = computed<Stay[]>(() => detail.value?.version.itinerary?.stays || []);
/** 当前看的版本的结构化行程 */
const itinerary = computed(() => detail.value?.version.itinerary);

/** 后台还在给选中的酒店、航班补其他平台的价格时，每 5 秒刷新一次当前版本，补完自动停 */
let stopPolling: (() => void) | null = null;
watch(
  () => (isCurrent.value && anyChecking(itinerary.value) ? detail.value?.version.version : null),
  (version) => {
    stopPolling?.();
    stopPolling = null;
    if (version == null) return;
    stopPolling = pollWhileChecking(async () => {
      const d = await api.trip(tripId.value);
      if (d.version.version !== detail.value?.version.version) return true;
      detail.value = d;
      return !anyChecking(d.version.itinerary);
    });
  },
);
onUnmounted(() => stopPolling?.());

/** 换航班：去机票比价页（带上行程，比完可以直接写进对应那天） */
/** 去机票比价：带上行程，出发地、目的地、去回日期和人数都由那边按行程填好并直接查 */
function changeFlight() {
  go(`/pages/search/flight?tab=flight&trip=${tripId.value}`);
}

/** 还没选具体航班的飞行（"航班待定"这类：交通项、没有航班号、名称或说明里提到航班/飞） */
function pendingFlight(item: ItineraryItem): boolean {
  return item.type === "TRANSPORT" && !flightNoOf(item) && !isBooked(item.id)
    && /航班|直飞|飞机|飞往|起飞|航空/.test(`${item.name} ${item.note || ""}`);
}
/** 当天晚上住的那段住宿；最后一天（离店日）显示离店的那家 */
const dayStay = computed(() => {
  const date = day.value?.date;
  if (!date) return undefined;
  return stays.value.find((st) => st.checkIn <= date && date < st.checkOut) || stays.value.find((st) => st.checkOut === date);
});
/** 时间线上酒店项对应的那段住宿：同一个高德地点，或者名字互相包含 */
function stayOfItem(it: ItineraryItem): Stay | undefined {
  return stays.value.find((st) => st.poiId && st.poiId === it.poiId) || stays.value.find((st) => it.name.includes(st.name) || st.name.includes(it.name));
}

/** 酒店项的价格：住宿比过价就用住宿的，没有就按当天入住去各平台现查 */
function itemPrice(it: ItineraryItem): HotelPrice | undefined {
  const st = stayOfItem(it);
  if (st?.price != null) return { loading: false, price: st.price, platform: st.platform, url: st.url };
  const [checkIn, checkOut] = stayDates(st, day.value?.date);
  return hotelPrice(it.name, city.value, checkIn, checkOut);
}

/** 名字上的链接：酒店优先用住宿的预订链接（比价后会更新），其次是这一项自己的、现查到的最低价链接 */
function itemLink(it: ItineraryItem): string | undefined {
  if (it.type !== "HOTEL") return it.url;
  return stayOfItem(it)?.url || it.url || itemPrice(it)?.url;
}

/** 行程没定住宿时推荐的附近酒店：按当天入住一晚查价 */
function nearbyPrice(h: Place) {
  const [checkIn, checkOut] = stayDates(null, day.value?.date);
  return hotelPrice(h.name, city.value, checkIn, checkOut);
}

/** 换酒店弹窗查价用的入住、离店日期 */
const replaceDates = computed(() => (replacing.value ? stayDates(stayOfItem(replacing.value) || dayStay.value, day.value?.date) : [undefined, undefined]));

// 当天的酒店项没有比价过的住宿、或者推荐了附近酒店时，去各平台查价
watch([day, stays, hotels], () => {
  for (const it of day.value?.items || []) {
    if (it.type !== "HOTEL") continue;
    const st = stayOfItem(it);
    if (st?.price != null) continue;
    const [checkIn, checkOut] = stayDates(st, day.value?.date);
    loadHotelPrices([it.name], city.value, checkIn, checkOut);
  }
  const [checkIn, checkOut] = stayDates(null, day.value?.date);
  loadHotelPrices(hotels.value.slice(0, 2).map((h) => h.name), city.value, checkIn, checkOut);
}, { immediate: true });

/** 住宿合计：每段每晚价格 × 晚数 */
const stayCost = computed(() => stays.value.reduce((s, st) => s + (st.price || 0) * nightsOf(st), 0));

function nightsOf(st: Stay) {
  const a = new Date(`${st.checkIn}T00:00:00`).getTime();
  const b = new Date(`${st.checkOut}T00:00:00`).getTime();
  return a && b && b > a ? Math.round((b - a) / 86400000) : 0;
}

/** 行程没有住宿信息时（早期的行程）：当天安排里的酒店项，没有就往前找最近一天的 */
const hotelInDay = computed(() => day.value?.items.find((x) => x.type === "HOTEL"));
const hotel = computed(() => {
  if (hotelInDay.value) return hotelInDay.value;
  for (let i = dayIndex.value - 1; i >= 0; i--) {
    const h = days.value[i].items.find((x) => x.type === "HOTEL");
    if (h) return h;
  }
  return undefined;
});
/** 当天概览：景点数、吃饭数、从第一项开始到最后一项结束的时长 */
const dayStats = computed(() => {
  const items = day.value?.items || [];
  const first = items.find((x) => x.start)?.start;
  const last = [...items].reverse().find((x) => x.end || x.start);
  const minutes = first && last ? toMin(last.end || last.start!) - toMin(first) : 0;
  return {
    spots: items.filter((x) => x.type === "SPOT").length,
    foods: items.filter((x) => x.type === "FOOD").length,
    hours: minutes > 0 ? `约 ${Math.round(minutes / 60)} 小时` : "待定",
    minutes,
  };
});
/** 按真实数据给的提醒：路上太久、没安排午饭、一天排太满、有地点没找到坐标 */
const suggestions = computed(() => {
  const out: { text: string; warn: boolean }[] = [];
  const d = day.value;
  if (!d) return out;
  const name = (id: string) => d.items.find((x) => x.id === id)?.name || "";
  for (const l of d.legs || []) {
    if (l.minutes >= 60) out.push({ text: `${name(l.from)} → ${name(l.to)} 路上要 ${duration2(l.minutes)}，可以考虑换近一点的地方`, warn: true });
  }
  const lunch = d.items.some((x) => x.type === "FOOD" && x.start && toMin(x.start) >= 600 && toMin(x.start) <= 840);
  if (!lunch && d.items.length > 2) out.push({ text: "这一天没有安排午饭，可以从中间的美食推荐里加一家", warn: true });
  if (dayStats.value.minutes >= 11 * 60) {
    out.push({ text: `这一天安排了${dayStats.value.hours}，带老人小孩的话可以删掉一两项`, warn: true });
  }
  const missing = d.items.filter((x) => EDITABLE.includes(x.type) && x.lng == null).length;
  if (missing && detail.value?.version.itinerary?.geo) out.push({ text: `有 ${missing} 个地点在高德上没对上，地图和路程里没算它们`, warn: false });
  if (!out.length) out.push({ text: "这一天的路线和时间都比较顺，没发现明显问题", warn: false });
  return out;
});

onLoad(async (query) => {
  tripId.value = Number(query?.id);
  if (TTABS.some((t) => t.key === query?.tab)) ttab.value = query!.tab as TripTab;
  if (!tripId.value) {
    uni.redirectTo({ url: "/pages/trips/trips" });
    return;
  }
  await Promise.all([load(), loadVersions(), loadEvents()]);
  loaded = true;
});

/** 从排序、替换页回来时刷新到最新版本 */
onShow(() => {
  if (loaded) load();
});

/** 换了一天或补完坐标后，按当天中心点查附近的美食、景点、酒店（只在电脑三栏里显示） */
watch(
  () => (wide.value && anchor.value ? `${anchor.value.lng},${anchor.value.lat}` : ""),
  async (key) => {
    if (!key || !anchor.value) {
      foods.value = [];
      spots.value = [];
      hotels.value = [];
      return;
    }
    const { lng, lat } = anchor.value as { lng: number; lat: number };
    nearLoading.value = true;
    const inTrip = new Set(days.value.flatMap((d) => d.items.map((x) => x.name)));
    try {
      const [f, s, h] = await Promise.all([
        api.places({ kind: "food", lng, lat, radius: 2000, size: 8 }),
        api.places({ kind: "spot", lng, lat, radius: 5000, size: 10 }),
        hotel.value || dayStay.value ? Promise.resolve([] as Place[]) : api.places({ kind: "hotel", lng, lat, radius: 3000, size: 5 }),
      ]);
      foods.value = f.filter((p) => !inTrip.has(p.name));
      spots.value = s.filter((p) => !inTrip.has(p.name) && (p.distance == null || p.distance > 150));
      hotels.value = h;
    } catch (e) {
      showError(e);
    } finally {
      nearLoading.value = false;
    }
  },
  { immediate: true },
);

function toMin(t: string) {
  const [h, m] = t.split(":").map(Number);
  return h * 60 + m;
}

function duration(it: ItineraryItem) {
  if (!it.start || !it.end) return it.type === "TRANSPORT" ? "" : "时长待定";
  const minutes = toMin(it.end) - toMin(it.start);
  // 到机场、车站这类只有一个时间点的不显示时长
  if (minutes <= 0) return "";
  if (minutes < 60) return `约 ${minutes} 分钟`;
  const hours = minutes / 60;
  return `约 ${Number.isInteger(hours) ? hours : hours.toFixed(1)} 小时`;
}

/** 没有照片时的图标：交通按内容分飞机、火车、汽车 */
function itemIcon(it: ItineraryItem): string {
  if (it.type !== "TRANSPORT") return TYPE_ICON[it.type] || "pin";
  const text = `${it.name} ${it.note || ""}`;
  if (flightNoOf(it) || /机场|航班|航空|飞机|飞往|直飞|起飞/.test(text)) return "plane";
  if (/高铁|动车|火车|车站|站$|地铁/.test(text) || /^[GDCKTZ]\d{1,4}/.test(it.name)) return "train";
  return "car";
}

/** 对话页是底部标签页，只能用 reLaunch 打开；其他页面正常压栈 */
function go(url: string) {
  if (url.startsWith("/pages/chat/chat")) uni.reLaunch({ url });
  else uni.navigateTo({ url });
}

function goBack() {
  const pages = getCurrentPages();
  if (pages.length > 1) uni.navigateBack();
  else uni.reLaunch({ url: "/pages/trips/trips" });
}

/** 切换标签，并把它写进地址栏（替换当前记录），刷新或复制链接都停在这个标签 */
function setTab(t: TripTab) {
  ttab.value = t;
  // #ifdef H5
  const url = `#/pages/trips/detail?id=${tripId.value}${t === "schedule" ? "" : `&tab=${t}`}`;
  history.replaceState(history.state, "", url);
  // #endif
}

function openInfo() {
  const t = trip.value;
  if (!t) return;
  infoError.value = "";
  info.value = { title: t.title, origin: t.origin || "", people: t.people ? String(t.people) : "", budget: t.budget != null ? String(t.budget) : "", currency: t.budgetCurrency || "CNY" };
}

async function saveInfo() {
  const f = info.value;
  if (!f) return;
  if (!f.title.trim()) return void (infoError.value = "请填写名称");
  if (f.people.trim() && !/^\d{1,2}$/.test(f.people.trim())) return void (infoError.value = "人数填 1 到 99");
  if (f.budget.trim() && !/^\d+(\.\d{1,2})?$/.test(f.budget.trim())) return void (infoError.value = "预算填数字，最多两位小数");
  infoSaving.value = true;
  try {
    detail.value!.trip = await api.updateTripInfo(tripId.value, {
      title: f.title.trim(), origin: f.origin.trim(), people: f.people.trim() ? Number(f.people) : null, budget: f.budget.trim() || null, budgetCurrency: f.currency,
    });
    info.value = null;
    uni.showToast({ title: "已保存", icon: "none" });
  } catch (e) {
    infoError.value = e instanceof Error ? e.message : String(e);
  } finally {
    infoSaving.value = false;
  }
}

/** 分享：把行程整理成一段文字复制下来，发给同行的人（没有公开链接，别人看不到你的账号数据） */
function share() {
  const t = trip.value;
  if (!t) return;
  const lines = [`${t.title}`, `${cnDate(t.startDate)} – ${cnDate(t.endDate)}${t.people ? ` · ${t.people} 人` : ""}${t.destination ? ` · ${t.destination}` : ""}`];
  days.value.forEach((d, i) => {
    lines.push("", `D${i + 1} ${cnDate(d.date)}${d.theme ? ` ${d.theme}` : ""}`);
    for (const it of d.items) if (it.type !== "TRANSPORT") lines.push(`${it.start || ""} ${it.name}`.trim());
  });
  uni.setClipboardData({
    data: lines.join("\n"),
    success: () => uni.showToast({ title: "行程已复制，可以粘贴发给同行的人", icon: "none" }),
    fail: () => uni.showToast({ title: "复制失败", icon: "none" }),
  });
}

function changeNeeds() {
  go("/pages/chat/chat?q=" + encodeURIComponent(`我想修改行程「${trip.value?.title}」（tripId=${tripId.value}）的需求`));
}

/** 让助手重排当天时可选的方向 */
const REGEN = ["太赶了，安排轻松点", "路线更顺，少走回头路", "换几个别的景点", "多安排点当地好吃的"];

/** 先选想怎么改，再带上要保留的项（标了保留、已预约、已去过的）交给助手，免得助手再反问 */
function regenerate() {
  uni.showActionSheet({
    itemList: REGEN,
    success: (r) => {
      const d = day.value;
      if (!d) return;
      const keep = d.items.filter((x) => x.locked || isBooked(x.id) || isDone(x.id)).map((x) => x.name);
      const text = `帮我重新安排行程「${trip.value?.title}」（tripId=${tripId.value}）的第${dayIndex.value + 1}天：${REGEN[r.tapIndex]}。`
        + (keep.length ? `这些保留不动：${keep.join("、")}。` : "没有必须保留的地方。")
        + "直接按这个改好保存。";
      go("/pages/chat/chat?q=" + encodeURIComponent(text));
    },
  });
}

function openFood() {
  go(`/pages/plan/food?id=${tripId.value}&day=${dayIndex.value + 1}`);
}

function openMap() {
  go(`/pages/plan/map?id=${tripId.value}&day=${dayIndex.value + 1}`);
}

/** 换一个 / 不感兴趣：电脑上弹窗，手机上进替换页 */
const bookedIds = computed(() => new Set(detail.value?.version.itinerary?.booked || []));

function isBooked(id: string) {
  return bookedIds.value.has(id);
}

function isDone(id: string) {
  return !!detail.value?.version.itinerary?.done?.includes(id);
}

/** 标记或取消"去过了 / 吃过了"：只记在当前版本上，不算新版本 */
async function toggleDone(item: ItineraryItem) {
  const done = !isDone(item.id);
  try {
    detail.value = await api.setDone(tripId.value, item.id, done);
  } catch (e) {
    showError(e);
  }
}

/** 整个行程改成坐地铁公交或打车，单独改过的路段一起恢复成默认 */
async function setTransport(mode: "TRANSIT" | "DRIVE") {
  if (mode === transport.value || modeBusy.value) return;
  modeBusy.value = true;
  try {
    detail.value = await api.setTransport(tripId.value, mode);
    uni.showToast({ title: mode === "DRIVE" ? "已改成打车" : "已改成坐地铁公交", icon: "none" });
  } catch (e) {
    showError(e);
  } finally {
    modeBusy.value = false;
  }
}

/** 单独改一段路怎么走 */
async function setLegMode(leg: ItineraryLeg, mode: "TRANSIT" | "DRIVE") {
  modeBusy.value = true;
  try {
    detail.value = await api.setLegMode(tripId.value, leg.from, leg.to, mode);
  } catch (e) {
    showError(e);
  } finally {
    modeBusy.value = false;
  }
}

/** 标记或取消"已预约"：只是记录，不会去平台下单或取消 */
async function toggleBooked(id: string, name: string) {
  const booked = !isBooked(id);
  try {
    detail.value = await api.markBooked(tripId.value, id, booked);
    uni.showToast({ title: booked ? `「${name}」已标记为已预约` : "已取消预约标记", icon: "none" });
  } catch (e) {
    showError(e);
  }
}

/** 已预约的项要换掉或删掉前先提醒去平台取消 */
function confirmBooked(item: ItineraryItem, action: () => void) {
  if (!isBooked(item.id)) {
    action();
    return;
  }
  uni.showModal({
    title: "这一项已预约",
    content: `「${item.name}」已经标记为已预约。换掉或删掉前记得去原平台取消预约，避免扣费或爽约。继续吗？`,
    confirmText: "继续",
    success: (res) => res.confirm && action(),
  });
}

function openReplace(item: ItineraryItem, reason: ChangeReason | null) {
  confirmBooked(item, () => doOpenReplace(item, reason));
}

function doOpenReplace(item: ItineraryItem, reason: ChangeReason | null) {
  if (!wide.value) {
    go(`/pages/plan/replace?id=${tripId.value}&item=${item.id}&reason=${reason || ""}`);
    return;
  }
  replaceReason.value = reason;
  replacing.value = item;
}

/** 修改保存成新版本后：换上新内容、刷新版本列表、提示可以看前后对比 */
/**
 * 导出 PDF：后端排版好直接下载。网页里新开标签页下载（新标签页带不了请求头，链接上带下载票据）；
 * 其他端先下载再用系统查看器打开。看历史版本时导出那个版本。
 */
async function downloadPdf() {
  if (!detail.value) return;
  const version = detail.value.version.version;
  const build = (ticket: string) =>
    `${API_BASE}/api/v1/trips/${tripId.value}/pdf?version=${version}&mt=${encodeURIComponent(ticket)}`;
  // #ifdef H5
  {
  // 先在点击的同一时刻打开新标签页，再填地址：等待之后才 window.open 会被 iPhone 的 Safari 当弹窗拦掉
  const win = window.open("", "_blank");
  const ticket = await ensureMediaTicket();
  if (!ticket) {
    win?.close();
    uni.showToast({ title: "登录状态失效，请重新登录", icon: "none" });
    return;
  }
  if (win) win.location.href = build(ticket);
  else window.location.href = build(ticket);
  uni.showToast({ title: "正在生成 PDF，十几秒后开始下载", icon: "none" });
  }
  // #endif
  // #ifndef H5
  {
  const ticket = await ensureMediaTicket();
  if (!ticket) {
    uni.showToast({ title: "登录状态失效，请重新登录", icon: "none" });
    return;
  }
  uni.showLoading({ title: "正在生成 PDF" });
  uni.downloadFile({
    url: build(ticket),
    success: (res) => uni.openDocument({ filePath: res.tempFilePath, fileType: "pdf", showMenu: true }),
    fail: () => uni.showToast({ title: "PDF 下载失败", icon: "none" }),
    complete: () => uni.hideLoading(),
  });
  }
  // #endif
}

function onSaved(d: TripDetail, note?: string) {
  const from = detail.value?.version.version || d.version.version - 1;
  replacing.value = null;
  detail.value = d;
  saved.value = { note: note || d.version.changeNote || "已修改", from, to: d.version.version };
  uni.showToast({ title: `已保存为第 ${d.version.version} 版`, icon: "none" });
  loadVersions();
}

async function toggleKeep(item: ItineraryItem) {
  try {
    detail.value = await api.lockItem(tripId.value, item.id, !item.locked);
    uni.showToast({ title: item.locked ? "已取消保留" : "已保留，重新规划时不会动它", icon: "none" });
  } catch (e) {
    showError(e);
  }
}

/** 更多操作：删掉这一项、调整顺序 */
function more(item: ItineraryItem) {
  uni.showActionSheet({
    itemList: ["删掉这一项", "调整这一天的顺序"],
    success: async (res) => {
      if (res.tapIndex === 1) {
        edit();
        return;
      }
      confirmBooked(item, async () => {
        try {
          onSaved(await api.removeItem(tripId.value, item.id, null));
        } catch (e) {
          showError(e);
        }
      });
    },
  });
}

/** 把附近的餐厅、景点、酒店加进当天 */
async function addPlace(p: Place, type: string) {
  try {
    const d = await api.addItem(tripId.value, dayIndex.value + 1, {
      name: p.name, poiId: p.id, address: p.address, lng: p.lng, lat: p.lat, photo: p.photo, rating: p.rating, cost: p.cost, type,
    });
    added[p.id] = true;
    onSaved(d);
  } catch (e) {
    showError(e);
  }
}

function dayCost(d: ItineraryDay) {
  return d.items.reduce((s, x) => s + (x.cost || 0), 0);
}

/** "2026-03-10" 显示成"3月10日"。 */
function cnDate(date?: string | null) {
  return date ? `${Number(date.slice(5, 7))}月${Number(date.slice(8, 10))}日` : "";
}

function switchTab(t: string) {
  if (t === "地图路线") {
    openMap();
    return;
  }
  if (t === "美食推荐") {
    openFood();
    return;
  }
  if (t === "景点门票") {
    go(`/pages/search/ticket?trip=${tripId.value}&day=${dayIndex.value + 1}`);
    return;
  }
  if (t === "机票比价") {
    go(`/pages/search/flight?tab=flight&trip=${tripId.value}`);
    return;
  }
  tab.value = t;
}

function edit() {
  go(`/pages/plan/edit?id=${tripId.value}&day=${dayIndex.value + 1}`);
}

function addStop() {
  go("/pages/chat/chat?q=" + encodeURIComponent(`行程「${trip.value?.title}」（tripId=${tripId.value}）第${dayIndex.value + 1}天再加一个景点`));
}

async function loadEvents() {
  try {
    events.value = await api.tripEvents(tripId.value);
  } catch (e) {
    showError(e);
  }
}

function eventTime(iso: string) {
  const d = new Date(iso);
  const w = mdw(d);
  return `${w.month}月${w.day}日 ${hm(d)}`;
}

async function confirmTrip() {
  try {
    detail.value!.trip = await api.confirmTrip(tripId.value);
    uni.showToast({ title: "已保存为行程", icon: "none" });
  } catch (e) {
    showError(e);
  }
}

/** 只剩一个版本时才在这里删整个行程；有多个版本时按钮只删正在看的那一版 */
function removeTrip() {
  uni.showModal({
    title: "删除行程",
    content: events.value.length ? `行程和 ${events.value.length} 条关联提醒都会删掉，找不回来。` : "删除这个行程？删掉后找不回来。",
    confirmText: "删除",
    confirmColor: "#B4442F",
    success: async (res) => {
      if (!res.confirm) return;
      try {
        await api.deleteTrip(tripId.value);
        goBack();
      } catch (e) {
        showError(e);
      }
    },
  });
}

/** 把正在看的历史版本存成新的当前版本，原来的版本都还在 */
async function useVersion() {
  const v = detail.value?.version.version;
  if (!v) return;
  try {
    const d = await api.restoreVersion(tripId.value, v);
    await load();
    await loadVersions();
    uni.showToast({ title: `已换回第 ${v} 版的内容（存为第 ${d.version.version} 版）`, icon: "none" });
  } catch (e) {
    showError(e);
  }
}

/** 只删正在看的这一版；删的是正在用的版本时，行程退回剩下的最新一版 */
function removeVersion() {
  const v = detail.value?.version.version;
  if (!v) return;
  const back = versions.value.find((x) => x.version !== v)?.version;
  uni.showModal({
    title: `删除第 ${v} 版`,
    content: isCurrent.value && back ? `只删这一版，行程退回到第 ${back} 版，其他版本都还在。` : "只删这一版，行程和其他版本都还在。",
    confirmText: "删除",
    confirmColor: "#B4442F",
    success: async (res) => {
      if (!res.confirm) return;
      try {
        await api.deleteTripVersion(tripId.value, v);
        await load();
        await loadVersions();
        uni.showToast({ title: `已删除第 ${v} 版`, icon: "none" });
      } catch (e) {
        showError(e);
      }
    },
  });
}

/** 读行程；当前版本还没补坐标就先显示已有内容，再让后端补，补完换上 */
async function load(version?: number) {
  try {
    const d = await api.trip(tripId.value, version);
    if (detail.value?.version.version !== d.version.version) dayIndex.value = Math.min(dayIndex.value, Math.max((d.version.itinerary?.days.length || 1) - 1, 0));
    detail.value = d;
    uni.setNavigationBarTitle({ title: d.trip.title });
    const it = d.version.itinerary;
    if (needsGeo(it) && d.version.version === d.trip.currentVer) {
      geoLoading.value = true;
      try {
        const g = await api.tripGeo(tripId.value);
        if (detail.value?.version.version === g.version.version) detail.value = g;
      } finally {
        geoLoading.value = false;
      }
    }
  } catch (e) {
    showError(e);
  }
}

async function loadVersions() {
  try {
    versions.value = await api.tripVersions(tripId.value);
  } catch (e) {
    showError(e);
  }
}
</script>

<style scoped>
/* ===== 电脑：行程编辑 ===== */
.editor { padding: 18px 24px 28px; max-width: 1440px; margin: 0 auto; box-sizing: border-box; }
.ed-head { display: flex; align-items: center; gap: 10px; }
.back { cursor: pointer; padding: 4px; }
.ed-title { font-size: 24px; font-weight: 800; }
.plan-chip { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; color: var(--accent); background: var(--accent-soft); padding: 3px 10px; border-radius: 8px; }
.ed-actions { margin-left: auto; display: flex; align-items: center; gap: 14px; }
.ed-actions .btn { margin: 0; }
.ghost-act { display: flex; align-items: center; gap: 4px; font-size: 14px; cursor: pointer; }
.ed-chips { display: flex; flex-wrap: wrap; gap: 10px; margin: 12px 0 16px; }
.ed-chip { display: flex; align-items: center; gap: 6px; font-size: 14px; padding: 8px 14px; border-radius: 10px; background: var(--surface); border: 1px solid var(--line); }
.ed-tag { font-size: 13px; padding: 8px 12px; border-radius: 10px; background: var(--surface-2); }
.ed-cols { display: grid; grid-template-columns: minmax(420px, 1.15fr) minmax(0, 1fr) 380px; gap: 16px; align-items: start; }
.ed-left, .ed-mid, .ed-right { display: flex; flex-direction: column; gap: 14px; min-width: 0; }
.ed-left { background: var(--surface); border: 1px solid var(--line); border-radius: 16px; padding: 14px; }
.ed-days { display: flex; gap: 8px; align-items: center; }
.ed-day { flex: 1; display: flex; align-items: baseline; justify-content: center; gap: 4px; padding: 9px 0; border-radius: 10px; background: var(--surface-2); cursor: pointer; }
.ed-day.on { background: var(--accent); color: #fff; }
.ed-day-no { font-weight: 800; }
.ed-day-date { font-size: 12px; opacity: 0.8; }
.add-day { display: flex; align-items: center; gap: 4px; font-size: 13px; color: var(--accent); border: 1px solid var(--accent); border-radius: 10px; padding: 8px 10px; cursor: pointer; white-space: nowrap; }
.day-h { display: flex; align-items: baseline; gap: 10px; margin-top: 4px; }
.day-h-no, .day-h-date { font-size: 20px; font-weight: 800; }
.day-h-theme { font-size: 18px; font-weight: 700; }
.day-h-sub { font-size: 13px; color: var(--muted); margin-top: -8px; }
.tl { display: flex; flex-direction: column; }
.tl-item { display: grid; grid-template-columns: 50px 16px minmax(0, 1fr); gap: 0 8px; position: relative; }
.tl-time { font-weight: 700; font-variant-numeric: tabular-nums; padding-top: 10px; }
.tl-dot { width: 12px; height: 12px; border-radius: 50%; border: 3px solid var(--accent); background: var(--surface); margin-top: 13px; box-sizing: border-box; z-index: 1; }
.tl-item::before { content: ""; position: absolute; left: 63px; top: 24px; bottom: -40px; width: 2px; background: var(--accent-soft); }
.tl-card { border: 1px solid var(--line); border-radius: 14px; padding: 10px; margin-bottom: 4px; }
.tl-main { display: flex; gap: 10px; }
.tl-img { width: 96px; height: 76px; border-radius: 10px; flex-shrink: 0; }
.tl-img.ph { display: flex; align-items: center; justify-content: center; background: var(--accent); }
.tl-img.ph.FOOD { background: var(--price); }
.tl-img.ph.mini { width: 40px; height: 40px; border-radius: 50%; background: var(--accent-soft); }
.tl-body { flex: 1; min-width: 0; }
.tl-name { font-weight: 800; font-size: 16px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.must { font-size: 11px; font-weight: 600; color: #e11d48; background: #fde8ec; padding: 1px 6px; border-radius: 4px; }
.tl-note { font-size: 13px; color: var(--muted); margin-top: 2px; }
.tl-facts { display: flex; flex-wrap: wrap; gap: 4px 14px; font-size: 12px; color: var(--muted); margin-top: 4px; }
.tl-facts text { display: inline-flex; align-items: center; gap: 3px; }
.tl-more { cursor: pointer; padding: 2px; }
.tl-card.locked { border-color: var(--ok); }
.kept-tag { font-size: 11px; font-weight: 600; color: var(--ok); background: var(--ok-soft); padding: 1px 6px; border-radius: 4px; }
.tl-facts .star { color: var(--price); font-weight: 700; }
.ver-chip, .mode-chip { gap: 4px; }
.ver { min-width: 22px; text-align: center; font-size: 12px; padding: 1px 6px; border-radius: 6px; cursor: pointer; }
.ver.on { background: var(--accent); color: #fff; }
.saved { display: flex; align-items: center; gap: 8px; padding: 10px 14px; margin-bottom: 14px; border-radius: 12px; background: var(--ok-soft); font-size: 14px; }
.saved-link { color: var(--accent); cursor: pointer; font-weight: 600; }
.saved-x { margin-left: auto; cursor: pointer; padding: 2px; }
.hero-ph { background: linear-gradient(135deg, #93c5fd, #2563eb); }
.ph { display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #93c5fd, #2563eb); }
.ph.FOOD { background: linear-gradient(135deg, #fdba74, #f97316); }
.pad { padding: 12px 0; }
.near-btn.done { color: var(--ok); background: var(--ok-soft); cursor: default; }
.stop-leg { margin-top: 8px; padding-top: 6px; border-top: 1px dashed var(--line); }
.m-mode { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 10px; }
.m-mode-seg { display: flex; background: var(--surface-2); border-radius: 8px; padding: 2px; }
.m-mode-item { font-size: 13px; padding: 4px 12px; border-radius: 6px; color: var(--muted); cursor: pointer; }
.m-mode-item.on { background: var(--surface); color: var(--accent); font-weight: 700; }
.tl-acts { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.a { display: flex; align-items: center; gap: 3px; font-size: 12px; padding: 5px 8px; border-radius: 8px; border: 1px solid var(--line); cursor: pointer; white-space: nowrap; }
.a.keep { color: var(--ok); background: var(--ok-soft); border-color: transparent; }
.a.keep.on { border-color: var(--ok); }
.a.swap { color: var(--accent); border-color: var(--accent); }
.tl-leg { padding: 10px 0 10px 74px; }
.tl-add { display: flex; align-items: center; justify-content: center; gap: 6px; padding: 12px; border: 1.5px dashed var(--line); border-radius: 12px; color: var(--accent); cursor: pointer; }
.day-hero { position: relative; border-radius: 16px; overflow: hidden; height: 300px; }
.day-hero-img { width: 100%; height: 100%; }
.overview { position: absolute; left: 12px; right: 12px; bottom: 12px; background: var(--surface); border-radius: 14px; padding: 12px 14px; box-shadow: 0 8px 24px rgba(0, 0, 0, 0.15); }
.ov-title { font-weight: 800; margin-bottom: 8px; }
.ov-grid, .ri-grid { display: grid; grid-template-columns: repeat(4, auto); justify-content: space-between; gap: 8px; }
.ri-grid { grid-template-columns: repeat(3, 1fr); }
.ov, .ri { display: flex; align-items: center; gap: 6px; }
.ov-k { font-size: 11px; color: var(--muted); }
.ov-v { font-size: 13px; font-weight: 700; white-space: nowrap; }
.panel { background: var(--surface); border: 1px solid var(--line); border-radius: 16px; padding: 14px; }
.panel-head { display: flex; justify-content: space-between; align-items: baseline; }
.panel-title { font-weight: 800; display: flex; align-items: center; gap: 6px; }
.more { font-size: 13px; color: var(--accent); cursor: pointer; }
.food { display: flex; gap: 10px; align-items: center; padding: 10px 0; border-bottom: 1px solid var(--line); }
.food:last-child { border-bottom: none; }
.food-img { width: 120px; height: 84px; border-radius: 10px; flex-shrink: 0; }
.food-body { flex: 1; min-width: 0; }
.food-name { font-weight: 800; }
.food-meta { font-size: 12px; }
.star { color: var(--price); font-weight: 700; }
.food-tags { display: flex; flex-wrap: wrap; gap: 4px; margin: 4px 0; }
.ftag { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: #fff1e6; color: #c2560c; }
.ftag.blue { background: var(--accent-soft); color: var(--accent); }
.add { margin: 0; color: var(--accent); flex-shrink: 0; }
.add.done { color: var(--ok); border-color: var(--ok); }
.hotel-side { display: flex; flex-direction: column; align-items: flex-end; gap: 6px; }
.price { color: var(--price); font-weight: 800; font-size: 18px; }
.qi { font-size: 11px; font-weight: 400; }
.seg { display: flex; background: var(--surface-2); border-radius: 10px; padding: 3px; margin-bottom: 10px; }
.seg-item { flex: 1; text-align: center; font-size: 13px; padding: 7px 0; border-radius: 8px; cursor: pointer; }
.seg-item.on { background: var(--accent); color: #fff; font-weight: 600; }
.r-map { width: 100%; height: 270px; border-radius: 12px; display: block; cursor: pointer; }
.empty-map { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; background: var(--surface-2); cursor: default; }
.route-info { margin-top: 12px; }
.ri-title { font-weight: 800; margin-bottom: 8px; }
.times { display: flex; flex-direction: column; gap: 6px; }
.time-row { display: flex; gap: 12px; font-size: 14px; }
.time-k { color: var(--accent); font-variant-numeric: tabular-nums; width: 96px; flex-shrink: 0; }
.near-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin-top: 10px; }
.near-img { width: 100%; height: 70px; border-radius: 8px; }
.near-name { font-weight: 700; font-size: 13px; margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.near-meta { font-size: 11px; color: var(--muted); }
.near-btn { font-size: 12px; text-align: center; color: var(--accent); background: var(--accent-soft); border-radius: 6px; padding: 4px 0; margin-top: 4px; cursor: pointer; }
.sg { display: flex; gap: 6px; align-items: flex-start; font-size: 13px; margin-top: 8px; }
.regen { margin: 12px 0 0; width: 100%; color: var(--accent); border-color: var(--accent); }
@media (max-width: 1400px) {
  .ed-cols { grid-template-columns: minmax(400px, 1fr) minmax(0, 1fr); }
  .ed-right { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(3, 1fr); }
}

/* ===== 手机 ===== */
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.head { margin-bottom: 10px; }
.title-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.title { font-size: 19px; font-weight: 700; }
.meta { font-size: 13px; color: var(--muted); margin-top: 2px; }
.tag { font-size: 11px; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--muted); }
.tag.ok { background: var(--ok-soft); color: var(--ok); }

.tabs { display: flex; gap: 22px; border-bottom: 1px solid var(--line); margin-bottom: 12px; }
.tab { padding: 8px 0; font-size: 14px; color: var(--muted); cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.tab.active { color: var(--accent); font-weight: 600; border-color: var(--accent); }

.days { white-space: nowrap; margin-bottom: 12px; }
.days-inner { display: inline-flex; gap: 8px; }
.day-tab {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  width: 64px;
  padding: 8px 0;
  border-radius: 12px;
  background: var(--surface);
  border: 1px solid var(--line);
  cursor: pointer;
}
.day-tab.active { background: var(--accent); border-color: var(--accent); color: #fff; }
.day-no { font-weight: 700; font-size: 16px; }
.day-date { font-size: 11px; opacity: 0.8; }

.timeline { display: flex; flex-direction: column; }
.day-theme { font-weight: 700; margin: 0 0 10px 2px; }
.stop { display: flex; gap: 10px; }
.rail { width: 24px; display: flex; flex-direction: column; align-items: center; position: relative; flex-shrink: 0; }
.rail::after { content: ""; position: absolute; top: 26px; bottom: -6px; border-left: 2px dashed var(--line); }
.stop:last-of-type .rail::after { display: none; }
.marker {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 14px;
  z-index: 1;
}
.marker.FOOD { background: var(--price); }
.marker.TRANSPORT, .marker.FREE { background: var(--muted); }
.stop-card { flex: 1; min-width: 0; background: var(--surface); border-radius: 14px; padding: 10px; margin-bottom: 10px; border: 1px solid var(--line); }
.stop-main { display: flex; gap: 10px; }
.thumb { width: 76px; height: 64px; border-radius: 10px; flex-shrink: 0; }
.placeholder { display: flex; align-items: center; justify-content: center; background: var(--accent); }
.placeholder.FOOD { background: var(--price); }
.placeholder.mini { width: 36px; height: 36px; border-radius: 50%; background: var(--accent-soft); }
.stop-body { flex: 1; min-width: 0; }
.stop-name { font-weight: 700; }
.stop-time { font-size: 12px; color: var(--muted); margin-top: 1px; font-variant-numeric: tabular-nums; }
.cost { color: var(--price); }
.cost.quiet { color: var(--muted); }
.checking { color: var(--accent); }
.stop-note { font-size: 13px; margin-top: 2px; color: var(--ink); opacity: 0.85; }
.stop-actions { display: flex; align-items: center; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
.act {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid var(--line);
  cursor: pointer;
}
.act.keep { color: var(--ok); border-color: var(--ok); }
.act.keep.on { background: var(--ok-soft); }
.act.ghost { color: var(--muted); }
.grip { margin-left: auto; padding: 2px; cursor: pointer; }
.add {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-left: 34px;
  padding: 12px;
  border: 1.5px dashed var(--accent);
  border-radius: 14px;
  color: var(--accent);
  font-size: 14px;
  cursor: pointer;
}

.costs { display: flex; flex-direction: column; gap: 10px; }
.cost-day { background: var(--surface); border-radius: 14px; padding: 12px 14px; border: 1px solid var(--line); }
.cost-day-head { display: flex; justify-content: space-between; font-weight: 600; }
.cost-row { display: flex; justify-content: space-between; font-size: 13px; margin-top: 4px; }
.cost-total { display: flex; justify-content: space-between; align-items: baseline; padding: 4px 4px 0; font-weight: 600; }
.big { font-size: 20px; }
.small { font-size: 12px; }

.tips { margin-top: 14px; background: var(--surface); border-radius: 14px; padding: 12px 14px; border: 1px solid var(--line); }
.tips-title { font-size: 13px; color: var(--muted); margin-bottom: 4px; }
.body { padding: 16px 18px; }
.versions { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 14px; }
.version { font-size: 12px; padding: 3px 10px; border-radius: 999px; border: 1px solid var(--line); cursor: pointer; }
.version.active { border-color: var(--accent); color: var(--accent); }
.reminders { margin-top: 12px; }
.reminder { display: flex; gap: 12px; padding: 4px 0; font-size: 14px; }
.reminder-time { color: var(--accent); font-variant-numeric: tabular-nums; flex-shrink: 0; }
.bottom { display: flex; gap: 10px; margin-top: 18px; }
.bottom .btn { margin: 0; flex: 1; }
.bottom .btn.danger { flex: 0 0 auto; }
.ed-head .btn { margin: 0; }
.m-stay { margin-top: 14px; padding: 12px; border-radius: 14px; background: var(--surface); border: 1px solid var(--line); display: flex; flex-direction: column; gap: 8px; }
.m-stay-title { display: flex; align-items: center; gap: 6px; font-weight: 700; font-size: 14px; }
.m-pdf { display: inline-flex; align-items: center; gap: 4px; margin-top: 6px; font-size: 13px; color: var(--accent); cursor: pointer; }
.amap-links { display: flex; gap: 12px; margin-top: 4px; }
.amap { display: inline-flex; align-items: center; gap: 2px; font-size: 12px; color: var(--accent); cursor: pointer; }
.done-btn { align-self: flex-start; margin: 2px 0; font-size: 12px; padding: 1px 8px; border-radius: 999px; border: 1px solid var(--line); color: var(--muted); cursor: pointer; }
.done-btn.on { border-color: transparent; background: var(--ok-soft); color: var(--ok); font-weight: 600; }
.booked-tag { margin-left: 6px; font-size: 11px; font-weight: 600; padding: 1px 6px; border-radius: 999px; background: var(--ok-soft); color: var(--ok); }
.a.book.on, .act.book.on { background: var(--ok-soft); border-color: var(--ok); color: var(--ok); }

/* 三个固定标签：日程 / 费用 / 预订信息 */
.ttabs { display: flex; gap: 28px; border-bottom: 1px solid var(--line); margin: 4px 0 16px; }
.ttab { padding: 10px 0; font-size: 15px; color: var(--muted); cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.ttab.on { color: var(--ink); font-weight: 600; border-bottom-color: var(--ink); }
.draft-note { margin-bottom: 8px; }
.m-ttabs { margin: 12px 0; }
.m-acts { display: flex; gap: 16px; }
.m-links { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.m-link { font-size: 13px; padding: 5px 12px; border-radius: 999px; border: 1px solid var(--line); background: var(--surface); color: var(--ink); }
.info-mask { position: fixed; inset: 0; z-index: 100; background: rgba(0, 0, 0, 0.3); display: flex; align-items: center; justify-content: center; padding: 16px; }
.info-sheet { width: 100%; max-width: 480px; background: var(--surface); border-radius: 14px; padding: 20px; display: flex; flex-direction: column; gap: 14px; box-sizing: border-box; }
.info-title { font-size: 16px; font-weight: 600; }
.info-row { display: flex; gap: 12px; }
.info-field { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.info-field.grow { flex: 1; }
.info-label { font-size: 13px; color: var(--muted); }
.info-picker { display: flex; align-items: center; min-width: 72px; }
.info-err { font-size: 13px; color: var(--danger); }
.info-acts { display: flex; justify-content: flex-end; gap: 10px; }
.info-acts .btn { margin: 0; }
@media (max-width: 1023px) {
  .info-mask { align-items: flex-end; padding: 0; }
  .info-sheet { max-width: none; border-radius: 14px 14px 0 0; padding-bottom: calc(20px + env(safe-area-inset-bottom, 0px)); }
}
</style>
