// The Report part of History: one week or one month summed up, the same figures the
// phone's weekly notification and the extension's options page print (report.js).

import { byId, el, fill } from './dom.js';
import { report, reportDayLabel, reportDeltaText, reportTitle } from './report.js';
import { state } from './state.js';
import { formatDistance, metersFromUm, reportDays } from './stats.js';
import { measureRow } from './today-view.js';

export const REPORTS = [
  { label: 'This week', span: 'week', offset: 0 },
  { label: 'Last week', span: 'week', offset: 1 },
  { label: 'This month', span: 'month', offset: 0 },
  { label: 'Last month', span: 'month', offset: 1 },
];

/** The shared limit in metres, when the account has one; the only limit this page knows. */
export function sharedLimitMetres() {
  return state.limit && state.limit.limitUm > 0 ? metersFromUm(state.limit.limitUm) : null;
}

export function renderReport() {
  const choice = REPORTS[state.report];
  const r = report(choice.span, reportDays(state.days.docs), {
    today: state.today,
    offset: choice.offset,
    limitMeters: sharedLimitMetres(),
  });
  byId('report-title').textContent = reportTitle(r);

  const host = byId('report-rows');
  if (r.totalMeters <= 0) {
    fill(host, el('li', { class: 'row fine', text: `Nothing was measured in this ${choice.span}.` }));
    return;
  }
  const rows = [
    measureRow({ label: 'Total', value: formatDistance(r.totalMeters), note: reportDeltaText(r) }),
    measureRow({
      label: 'Daily average',
      value: formatDistance(r.dailyAverageMeters),
      note: `${r.activeDays} of ${r.elapsedDays} days tracked${r.running ? ', so far' : ''}`,
    }),
  ];
  if (r.cleanDays !== null) {
    rows.push(measureRow({ label: 'Days within your limit', value: `${r.cleanDays} of ${r.judgedDays}` }));
  }
  rows.push(measureRow({
    label: 'Highest day', value: formatDistance(r.highest.meters), note: reportDayLabel(r.highest.date),
  }));
  // With one tracked day the lowest is the highest again; saying it twice adds nothing.
  if (r.activeDays > 1) {
    rows.push(measureRow({
      label: 'Lowest day', value: formatDistance(r.lowest.meters), note: reportDayLabel(r.lowest.date),
    }));
  }
  const top = Math.max(r.apps[0]?.meters ?? 0, 1);
  for (const app of r.apps) {
    const change = app.previousMeters <= 0 || app.previousMeters === app.meters
      ? null
      : `${app.meters > app.previousMeters ? 'Up' : 'Down'} from ${formatDistance(app.previousMeters)}`;
    rows.push(measureRow({
      label: app.key, value: formatDistance(app.meters), note: change, fraction: app.meters / top,
    }));
  }
  fill(host, rows);
}
