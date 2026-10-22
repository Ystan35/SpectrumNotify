// Browser-console script: combines um-timetable-sdk's main.js + lecturer.js into one paste.
// Run on https://cloud.timeedit.net/my_um/web/students/ while logged in (F12 > Console).
// It downloads lecturer_data.json first (fast), then course_events_with_details.json (slow).
// Afterwards run tools\update_catalog.bat to build and push catalog.json.

// --------------------------- part 1: lecturers (from lecturer.js) ---------------------------
async function fetchLecturerInfo(lecturerId) {
  const url = `${BASE}objects/${lecturerId}/o.json?fr=t&types=15&sid=5&l=en_US`;
  try {
    const response = await fetch(url);
    if (!response.ok) return null;
    const data = await response.json();
    return data.hasOwnProperty("Job Category") ? { id: lecturerId, details: data } : null;
  } catch (error) {
    console.error(`Fetch error for lecturer ID ${lecturerId}:`, error);
    return null;
  }
}

async function collectLecturers() {
  const startLecturerId = 8600;
  const endLecturerId = 13700;
  const batchSize = 20;
  const out = [];
  console.log(`Lecturers: fetching IDs ${startLecturerId} to ${endLecturerId}...`);
  for (let i = startLecturerId; i <= endLecturerId; i += batchSize) {
    const batchIds = [];
    for (let j = 0; j < batchSize && (i + j) <= endLecturerId; j++) batchIds.push(i + j);
    const results = await Promise.all(batchIds.map(fetchLecturerInfo));
    out.push(...results.filter(r => r !== null));
    console.log(`  Lecturers up to ${batchIds[batchIds.length - 1]} (found ${out.length} so far)`);
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  console.log("Lecturers done:", out.length);
  download(JSON.stringify(out, null, 2), "lecturer_data.json");
}

// --------------------------- part 2: courses + events (from main.js) ---------------------------
async function getObjectDetails(objectId) {
  try {
    const r = await fetch(`${BASE}objects/${objectId}/o.json?fr=t&types=15&sid=5&l=en_US`);
    return r.ok ? await r.json() : null;
  } catch (_) { return null; }
}

async function getCourseEvents(courseId) {
  try {
    const response = await fetch(BASE + "ri.json?h=f&sid=3&p=0.m,12.n&objects=" + courseId + "&ox=0&types=0&fe=0&h2=f&l=en_EN");
    if (!response.ok) return null;
    const data = await response.json();
    if (!data || !data.reservations || !data.columnheaders) return null;
    data.reservations = data.reservations.map(rese => ({
      ...rese,
      additional_info: data.columnheaders.reduce((obj, key, index) => { obj[key] = rese.columns[index]; return obj; }, {}),
    }));
    return data;
  } catch (error) {
    console.error("Error fetching course events:", error.message);
    return null;
  }
}

async function getReservationDetailsHtml(reservationId) {
  try {
    const url = `${BASE}ri.html?h=f&sid=3&types=4&fe=0&h2=f&l=en_EN&id=${reservationId}&fr=t&step=0&ef=2&nocache=2`;
    const r = await fetch(url);
    return r.ok ? await r.text() : null;
  } catch (_) { return null; }
}

async function parseReservationDetailsFromHtml(html) {
  if (!html) return null;
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const details = {};
  const table = doc.querySelector('.detailedResObjects');
  if (table) {
    for (const row of Array.from(table.querySelectorAll('tr'))) {
      const name = row.querySelector('.columnname');
      const value = row.querySelector('.pr');
      if (name && value) details[name.textContent.trim()] = value.textContent.trim();
    }
  }
  return details;
}

async function getAllObjects(maxItems) {
  let allObjects = [];
  let currentStart = 0;
  let totalObjects = null;
  const pageSize = 100;
  const objectsBaseUrl = `${BASE}objects.html?max=${pageSize}&fr=t&partajax=t&im=f&sid=4&l=en_US&objects=&types=15&part=t&media=html`;

  while (allObjects.length < maxItems) {
    const currentUrl = `${objectsBaseUrl}&start=${currentStart}`;
    try {
      const response = await fetch(currentUrl);
      if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
      const doc = new DOMParser().parseFromString(await response.text(), 'text/html');

      const resolvedObjects = await Promise.all(Array.from(doc.querySelectorAll('[data-idonly]')).map(async itemDiv => {
        const objectId = itemDiv.dataset.idonly;
        const object = {
          id: itemDiv.dataset.id,
          idOnly: objectId,
          type: itemDiv.dataset.type,
          name: itemDiv.dataset.name,
          details: await getObjectDetails(objectId),
        };
        const events = await getCourseEvents(objectId);
        object.events = events ? events.reservations : null;
        if (object.events) {
          const results = await Promise.all(object.events.map(async reservation => ({
            reservation,
            details: await parseReservationDetailsFromHtml(await getReservationDetailsHtml(reservation.id)),
          })));
          results.forEach((r, i) => { object.events[i].html_details = r.details; });
        }
        return object;
      }));

      allObjects.push(...resolvedObjects);

      const countEl = doc.querySelector('.searchResultCount');
      if (countEl) totalObjects = parseInt(countEl.textContent.trim().replace(/\D/g, ''), 10);
      console.log(`Objects ${currentStart + 1}-${currentStart + resolvedObjects.length} processed.`);

      if (totalObjects !== null && allObjects.length >= Math.min(totalObjects, maxItems)) break;
      if (resolvedObjects.length === 0) break;
      currentStart += pageSize;
    } catch (error) {
      console.error("Error fetching data:", error);
      break;
    }
  }
  return allObjects.slice(0, maxItems);
}

async function collectCourses(maxItems) {
  console.log(`Courses: fetching up to ${maxItems} objects...`);
  const objects = await getAllObjects(maxItems);
  console.log("Courses done:", objects.length);
  download(JSON.stringify(objects, null, 2), "course_events_with_details.json");
}

// --------------------------- shared helpers + start ---------------------------
const BASE = "https://cloud.timeedit.net/my_um/web/students/";
const MAX_ITEMS = 29000; // <---- SET YOUR MAX ITEMS HERE

function download(text, filename) {
  const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
  console.log(`Downloaded ${filename}`);
}

(async () => {
  await collectLecturers();
  await collectCourses(MAX_ITEMS);
  console.log("All done - two JSON files downloaded. Now run tools\\update_catalog.bat on your PC.");
})();
